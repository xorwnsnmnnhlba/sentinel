package com.sentinel.application.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.sentinel.application.ChangeLogService;
import com.sentinel.application.ChangeLogService.Target;
import com.sentinel.application.InvalidRequestException;
import com.sentinel.application.NotFoundException;
import com.sentinel.domain.AccessToken;
import com.sentinel.domain.ChangeLog.Action;
import com.sentinel.domain.ChangeLog.TargetType;
import com.sentinel.infra.persistence.AccessTokenRepository;
import com.sentinel.infra.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CI 등 자동화용 API 토큰 발급·폐기·확인. 토큰으로는 실행 시작·결과 조회만 허용 ({@code AuthFilter}).
 * <p>
 * 원문은 {@link #PREFIX} + 무작위 256비트로 발급 응답에만 전달하고 SHA-256 해시로 저장 (세션 토큰과 같은 방식).
 * 접두어로 세션 토큰과 구분하고 유출 검사 도구가 알아볼 수 있게 함. 발급·폐기는 변경 이력에 기록 (값은 남기지 않음)
 */
@Service
@RequiredArgsConstructor
public class AccessTokenService {

	public static final String PREFIX = "snt_";

	/** 발급 때 고르는 유효 기간(일). 만료 없는 토큰은 만들지 않음 (잊힌 토큰이 계속 유효하지 않도록) */
	public static final Set<Integer> VALIDITY_DAYS = Set.of(30, 90, 180, 365);

	/** 목록에 보여 주는 앞부분 길이 (접두어 포함) */
	private static final int SHOWN_LENGTH = 8;

	private final AccessTokenRepository tokens;

	private final UserRepository users;

	private final ChangeLogService changeLog;

	private final Clock clock;

	/**
	 * @return 토큰 원문(이 응답에서만)과 목록 항목
	 * @throws InvalidRequestException 이름이 비었거나 100자 초과, 허용하지 않는 유효 기간
	 */
	@Transactional
	public Issued issue(long userId, String name, int validityDays) {
		if (!VALIDITY_DAYS.contains(validityDays)) {
			throw new InvalidRequestException("validityDays must be one of 30, 90, 180, 365");
		}
		String token = PREFIX + AuthService.newToken();
		Instant now = clock.instant();
		AccessToken saved;
		try {
			saved = tokens.save(new AccessToken(userId, name, AuthService.hash(token), token.substring(0, SHOWN_LENGTH),
					now, Duration.ofDays(validityDays)));
		}
		catch (IllegalArgumentException ex) {
			throw new InvalidRequestException(ex.getMessage());
		}
		changeLog.record(userId, target(saved), Action.CREATED);
		return new Issued(token, AccessTokenItem.of(saved, now));
	}

	/** 폐기되지 않은 토큰 (만료 포함), 최근 발급 순 */
	@Transactional(readOnly = true)
	public List<AccessTokenItem> list(long userId) {
		Instant now = clock.instant();
		return tokens.findByUserIdAndRevokedAtIsNullOrderByIdDesc(userId)
			.stream()
			.map(token -> AccessTokenItem.of(token, now))
			.toList();
	}

	/**
	 * 폐기. 이후 이 토큰의 요청은 401
	 * @throws NotFoundException 없거나 다른 사용자의 토큰이거나 이미 폐기됨
	 */
	@Transactional
	public void revoke(long tokenId, long userId) {
		AccessToken token = tokens.findByIdAndUserIdAndRevokedAtIsNull(tokenId, userId)
			.orElseThrow(() -> new NotFoundException("access token", tokenId));
		token.revoke(clock.instant());
		changeLog.record(userId, target(token), Action.DELETED);
	}

	/**
	 * 토큰으로 사용자 확인. 접두어가 없거나 모르는 토큰, 만료·폐기된 토큰, 삭제된 사용자면 빈 값. 확인되면 마지막 사용 시각 기록
	 */
	@Transactional
	public Optional<AuthenticatedUser> authenticate(String token) {
		if (token == null || !token.startsWith(PREFIX)) {
			return Optional.empty();
		}
		Instant now = clock.instant();
		return tokens.findByTokenHash(AuthService.hash(token))
			.filter(found -> found.isUsable(now))
			.flatMap(found -> users.findByIdAndArchivedAtIsNull(found.getUserId()).map(user -> {
				found.markUsed(now);
				return AuthenticatedUser.of(user);
			}));
	}

	private static Target target(AccessToken token) {
		return new Target(TargetType.ACCESS_TOKEN, token.getId(), token.getName(), null);
	}

	/**
	 * @param token 토큰 원문. 다시 조회할 수 없음
	 */
	public record Issued(String token, AccessTokenItem accessToken) {
	}

	/**
	 * @param prefix 토큰 앞부분 (예: snt_Ab12). 나머지는 보관하지 않음
	 * @param lastUsedAt 마지막 사용 시각. 쓴 적 없으면 null
	 * @param expired 만료됨. 폐기된 토큰은 목록에 없음
	 */
	public record AccessTokenItem(long id, String name, String prefix, Instant createdAt, Instant expiresAt,
			Instant lastUsedAt, boolean expired) {

		static AccessTokenItem of(AccessToken token, Instant now) {
			return new AccessTokenItem(token.getId(), token.getName(), token.getTokenPrefix(), token.getCreatedAt(),
					token.getExpiresAt(), token.getLastUsedAt(), !now.isBefore(token.getExpiresAt()));
		}

	}

}
