package com.sentinel.application.auth;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

import com.sentinel.domain.User;
import com.sentinel.domain.UserSession;
import com.sentinel.infra.persistence.UserRepository;
import com.sentinel.infra.persistence.UserSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로그인·로그아웃과 세션 확인.
 * <p>
 * 비밀번호는 BCrypt 해시로만 저장하고, 세션 토큰은 무작위 값의 SHA-256 해시로 저장.
 * 로그인 실패 사유(없는 계정·비밀번호 불일치)는 구분해서 알려주지 않음 (계정 존재 여부 노출 방지).
 */
@Service
@RequiredArgsConstructor
public class AuthService {

	private static final SecureRandom RANDOM = new SecureRandom();

	private final UserRepository users;

	private final UserSessionRepository sessions;

	private final AuthProperties properties;

	private final Clock clock;

	private final LoginThrottle loginThrottle;

	private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

	/**
	 * 없는 계정의 로그인에도 같은 시간이 걸리도록 비교할 해시. 기동 시 한 번 만든 실제 BCrypt 해시
	 * (형식이 틀린 해시는 비교 없이 바로 실패해 응답 시간으로 계정 유무가 드러남)
	 */
	private final String dummyHash = passwordEncoder.encode(newToken());

	/**
	 * @return 세션 토큰 원문. 저장하지 않으며 응답 후 다시 조회 불가
	 * @throws InvalidCredentialsException 계정 없음·보관됨·비밀번호 불일치
	 * @throws TooManyLoginAttemptsException 반복된 실패로 잠긴 이메일 ({@link LoginThrottle})
	 */
	@Transactional
	public Login login(String email, String password) {
		String normalized = normalize(email);
		Instant now = clock.instant();
		loginThrottle.checkAllowed(normalized, now);
		User user = users.findByEmailAndArchivedAtIsNull(normalized).orElse(null);
		if (user == null || password == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
			// 존재하지 않는 계정도 같은 시간이 걸리도록 더미 해시와 비교
			if (user == null && password != null) {
				passwordEncoder.matches(password, dummyHash);
			}
			loginThrottle.recordFailure(normalized, now);
			throw new InvalidCredentialsException();
		}
		loginThrottle.recordSuccess(normalized);
		String token = newToken();
		sessions.save(new UserSession(hash(token), user.getId(), clock.instant(), properties.sessionValidity()));
		return new Login(token, AuthenticatedUser.of(user));
	}

	/**
	 * 토큰으로 사용자 확인. 만료된 세션은 정리하고 빈 값 반환
	 */
	@Transactional
	public Optional<AuthenticatedUser> authenticate(String token) {
		if (token == null || token.isBlank()) {
			return Optional.empty();
		}
		UserSession session = sessions.findByTokenHash(hash(token)).orElse(null);
		if (session == null) {
			return Optional.empty();
		}
		Instant now = clock.instant();
		if (session.isExpired(now)) {
			sessions.delete(session);
			return Optional.empty();
		}
		Optional<User> user = users.findByIdAndArchivedAtIsNull(session.getUserId());
		if (user.isEmpty()) {
			// 삭제된 사용자의 세션은 더 이상 사용 불가
			sessions.delete(session);
			return Optional.empty();
		}
		session.markUsed(now);
		return user.map(AuthenticatedUser::of);
	}

	@Transactional
	public void logout(String token) {
		if (token != null && !token.isBlank()) {
			sessions.deleteByTokenHash(hash(token));
		}
	}

	/** 비밀번호 변경 시 다른 기기의 세션까지 모두 종료 */
	@Transactional
	public void logoutEverywhere(long userId) {
		sessions.deleteByUserId(userId);
	}

	String encodePassword(String password) {
		return passwordEncoder.encode(password);
	}

	public boolean matches(String rawPassword, String passwordHash) {
		return rawPassword != null && passwordEncoder.matches(rawPassword, passwordHash);
	}

	static String normalize(String email) {
		return email == null ? "" : email.strip().toLowerCase();
	}

	/** 초대·재설정 토큰도 같은 방식으로 생성 */
	static String newToken() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	/** 토큰은 무작위 값이라 사전 공격 대상이 아니므로 SHA-256으로 충분. 비밀번호와 달리 BCrypt 불필요 */
	static String hash(String token) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		}
		catch (java.security.NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is required", ex);
		}
	}

	/**
	 * @param token 세션 토큰 원문
	 */
	public record Login(String token, AuthenticatedUser user) {
	}

	/** 테스트용. 없는 계정 비교에 쓰는 해시 */
	String dummyHash() {
		return dummyHash;
	}

}
