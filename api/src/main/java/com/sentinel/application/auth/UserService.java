package com.sentinel.application.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.sentinel.application.ChangeLogService;
import com.sentinel.application.ChangeLogService.Target;
import com.sentinel.application.ConflictException;
import com.sentinel.application.InvalidRequestException;
import com.sentinel.application.NotFoundException;
import com.sentinel.domain.ChangeLog.Action;
import com.sentinel.domain.ChangeLog.TargetType;
import com.sentinel.domain.User;
import com.sentinel.domain.UserToken;
import com.sentinel.infra.persistence.UserRepository;
import com.sentinel.infra.persistence.UserTokenRepository;
import com.sentinel.runner.InvalidValueException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자 관리와 초대·비밀번호 재설정.
 * <p>
 * 가입은 관리자 초대로만 이루어지며, 초대·재설정 링크의 토큰은 1회용이고 만료가 있음.
 * 토큰 원문은 발급 응답에서만 확인할 수 있고 저장은 해시로만 함.
 * <p>
 * 초대 발급·취소, 가입(초대 수락), 재설정 링크 발급, 비밀번호 변경·재설정, 삭제는 변경 이력을 남김. 비밀번호·토큰 값은 남기지 않음
 */
@Service
@RequiredArgsConstructor
public class UserService {

	/** 짧은 비밀번호를 막기 위한 최소 길이 */
	static final int MIN_PASSWORD_LENGTH = 10;

	private final UserRepository users;

	private final UserTokenRepository tokens;

	private final AuthService authService;

	private final AccessTokenService accessTokens;

	private final AuthProperties properties;

	private final Clock clock;

	private final ChangeLogService changeLog;

	@Transactional(readOnly = true)
	public List<UserSummary> list() {
		return users.findByArchivedAtIsNullOrderByEmail().stream().map(UserSummary::of).toList();
	}

	/**
	 * @throws NotFoundException 미존재 또는 삭제
	 */
	@Transactional(readOnly = true)
	public UserSummary get(long userId) {
		return users.findByIdAndArchivedAtIsNull(userId)
			.map(UserSummary::of)
			.orElseThrow(() -> new NotFoundException("user", userId));
	}

	/**
	 * 초대 링크 발급. 이미 있는 계정이면 거부
	 * @return 토큰 원문. 저장하지 않으므로 이 응답에서만 확인 가능
	 */
	@Transactional
	public IssuedToken invite(String email, User.Role role, long invitedBy) {
		String normalized = normalizedEmail(email);
		if (users.findByEmailAndArchivedAtIsNull(normalized).isPresent()) {
			throw new ConflictException("email '" + normalized + "' is already in use");
		}
		String token = AuthService.newToken();
		UserToken saved = tokens.save(UserToken.invite(AuthService.hash(token), normalized, role, clock.instant(),
				properties.inviteValidity(), invitedBy));
		changeLog.record(invitedBy, new Target(TargetType.INVITE, saved.getId(), normalized, null), Action.CREATED);
		return new IssuedToken(token, saved.getExpiresAt());
	}

	/**
	 * 대기 중인 초대 (수락·만료·취소되지 않은 것), 최근 발급 순. 토큰 원문은 저장하지 않으므로 링크는 다시 볼 수 없음
	 */
	@Transactional(readOnly = true)
	public List<PendingInvite> pendingInvites() {
		List<UserToken> invites = tokens
			.findByTypeAndUsedAtIsNullAndExpiresAtAfterOrderByIdDesc(UserToken.Type.INVITE, clock.instant());
		// 발급한 관리자가 나중에 삭제되었어도 이메일은 표시 (삭제는 보관이므로 행이 남음)
		Map<Long, User> inviters = users
			.findAllById(invites.stream().map(UserToken::getCreatedBy).distinct().toList())
			.stream()
			.collect(Collectors.toMap(User::getId, Function.identity()));
		return invites.stream().map(invite -> PendingInvite.of(invite, inviters.get(invite.getCreatedBy()))).toList();
	}

	/**
	 * @throws NotFoundException 없거나 수락·만료·취소된 초대
	 */
	@Transactional(readOnly = true)
	public PendingInvite pendingInvite(long inviteId) {
		UserToken invite = pending(inviteId);
		return PendingInvite.of(invite, users.findById(invite.getCreatedBy()).orElse(null));
	}

	/**
	 * 초대 취소. 보낸 링크는 바로 쓸 수 없게 됨 (확인·수락 모두 404)
	 * @throws NotFoundException 없거나 수락·만료·취소된 초대
	 */
	@Transactional
	public void revokeInvite(long inviteId, long revokedBy) {
		UserToken invite = pending(inviteId);
		tokens.delete(invite);
		changeLog.record(revokedBy, new Target(TargetType.INVITE, invite.getId(), invite.getEmail(), null),
				Action.DELETED);
	}

	/** 초대 링크 확인. 화면에서 대상 이메일을 보여주기 위한 용도 */
	@Transactional(readOnly = true)
	public InviteInfo invite(String token) {
		UserToken invite = usable(token, UserToken.Type.INVITE);
		return new InviteInfo(invite.getEmail(), invite.getExpiresAt());
	}

	/**
	 * 초대 수락. 초대받은 이메일·역할로 계정을 만듦 (로그인은 따로)
	 * @throws NotFoundException 만료·사용·위조된 초대 (구분하지 않음)
	 * @throws InvalidRequestException 짧은 비밀번호
	 * @throws ConflictException 수락 직전에 같은 이메일로 계정이 만들어진 경우
	 */
	@Transactional
	public UserSummary acceptInvite(String token, String name, String password) {
		UserToken invite = usable(token, UserToken.Type.INVITE);
		requireStrongPassword(password);
		if (users.findByEmailAndArchivedAtIsNull(invite.getEmail()).isPresent()) {
			throw new ConflictException("email '" + invite.getEmail() + "' is already in use");
		}
		claim(invite);
		User user = users.save(new User(invite.getEmail(), name, authService.encodePassword(password), invite.getRole()));
		// 같은 이메일로 다시 보낸 다른 초대도 무효화. 남겨 두면 계정 삭제 뒤 예전 링크로 다시 가입할 수 있음
		tokens.deleteByTypeAndEmailAndUsedAtIsNull(UserToken.Type.INVITE, invite.getEmail());
		changeLog.record(user.getId(), target(user), Action.CREATED);
		return UserSummary.of(user);
	}

	/**
	 * 관리자가 재설정 링크 발급. 비밀번호를 대신 정해 주지 않고 사용자가 직접 입력하도록 함
	 */
	@Transactional
	public IssuedToken createPasswordReset(long userId, long createdBy) {
		User user = users.findByIdAndArchivedAtIsNull(userId)
			.orElseThrow(() -> new NotFoundException("user", userId));
		String token = AuthService.newToken();
		UserToken saved = tokens.save(UserToken.passwordReset(AuthService.hash(token), user.getId(), clock.instant(),
				properties.passwordResetValidity(), createdBy));
		changeLog.record(createdBy, target(user), Action.UPDATED, List.of("passwordResetLink"));
		return new IssuedToken(token, saved.getExpiresAt());
	}

	/** 재설정 링크 확인 */
	@Transactional(readOnly = true)
	public ResetInfo passwordReset(String token) {
		UserToken reset = usable(token, UserToken.Type.PASSWORD_RESET);
		User user = users.findByIdAndArchivedAtIsNull(reset.getUserId())
			.orElseThrow(() -> new NotFoundException("user", reset.getUserId()));
		return new ResetInfo(user.getId(), user.getEmail(), reset.getExpiresAt());
	}

	/**
	 * 링크로 비밀번호 재설정. 기존 세션은 모두 종료하여 탈취된 세션을 끊음
	 * @throws NotFoundException 만료·사용·위조된 링크, 다른 사용자의 링크 (구분하지 않음)
	 */
	@Transactional
	public void resetPassword(long userId, String token, String password) {
		UserToken reset = usable(token, UserToken.Type.PASSWORD_RESET);
		if (reset.getUserId() == null || reset.getUserId() != userId) {
			throw new NotFoundException("link", 0);
		}
		requireStrongPassword(password);
		User user = users.findByIdAndArchivedAtIsNull(reset.getUserId())
			.orElseThrow(() -> new NotFoundException("user", reset.getUserId()));
		claim(reset);
		user.changePasswordHash(authService.encodePassword(password));
		authService.logoutEverywhere(user.getId());
		// 재설정은 계정 복구 절차이므로 탈취된 세션으로 발급했을 수 있는 API 토큰도 폐기 (본인 비밀번호 변경은 CI가 끊기지 않도록 유지)
		accessTokens.revokeAll(user.getId());
		changeLog.record(user.getId(), target(user), Action.UPDATED, List.of("password"));
	}

	/**
	 * 본인 비밀번호 변경. 현재 비밀번호를 확인하고 다른 기기의 세션은 모두 종료
	 * @throws InvalidCredentialsException 현재 비밀번호 불일치
	 */
	@Transactional
	public void changePassword(long userId, String currentPassword, String newPassword) {
		User user = users.findByIdAndArchivedAtIsNull(userId)
			.orElseThrow(() -> new NotFoundException("user", userId));
		if (!authService.matches(currentPassword, user.getPasswordHash())) {
			throw new InvalidCredentialsException();
		}
		requireStrongPassword(newPassword);
		user.changePasswordHash(authService.encodePassword(newPassword));
		authService.logoutEverywhere(userId);
		changeLog.record(userId, target(user), Action.UPDATED, List.of("password"));
	}

	/**
	 * 사용자 삭제(보관). 세션과 남은 초대·재설정 토큰을 함께 정리
	 * @throws InvalidRequestException 마지막 관리자이거나 자기 자신인 경우
	 */
	@Transactional
	public void delete(long userId, long requestedBy) {
		User user = users.findByIdAndArchivedAtIsNull(userId)
			.orElseThrow(() -> new NotFoundException("user", userId));
		if (userId == requestedBy) {
			throw new InvalidRequestException("you cannot delete your own account");
		}
		if (user.isAdmin() && adminCount() <= 1) {
			throw new InvalidRequestException("the last administrator cannot be deleted");
		}
		user.archive(clock.instant());
		authService.logoutEverywhere(userId);
		tokens.deleteByUserId(userId);
		// 이 이메일로 보낸 남은 초대도 삭제. 남겨 두면 삭제된 사람이 예전 링크로 다시 가입할 수 있음
		tokens.deleteByTypeAndEmailAndUsedAtIsNull(UserToken.Type.INVITE, user.getEmail());
		changeLog.record(requestedBy, target(user), Action.DELETED);
	}

	private static Target target(User user) {
		return new Target(TargetType.USER, user.getId(), user.getEmail(), null);
	}

	private long adminCount() {
		return users.findByArchivedAtIsNullOrderByEmail().stream().filter(User::isAdmin).count();
	}

	private UserToken pending(long inviteId) {
		return tokens.findById(inviteId)
			.filter(token -> token.getType() == UserToken.Type.INVITE && token.isUsable(clock.instant()))
			.orElseThrow(() -> new NotFoundException("invite", inviteId));
	}

	/**
	 * 링크를 이 요청이 사용한 것으로 기록. 읽은 뒤 그사이 같은 링크로 다른 요청이 먼저 썼거나 취소·삭제되면 404
	 * (엔티티 갱신은 마지막 쓰기가 이겨 1회용 링크가 두 번 쓰일 수 있었음). 실패하면 트랜잭션이 되돌려져 계정 생성·비밀번호 변경도 취소
	 */
	private void claim(UserToken token) {
		if (tokens.markUsed(token.getId(), clock.instant()) == 0) {
			throw new NotFoundException("link", 0);
		}
	}

	private UserToken usable(String token, UserToken.Type type) {
		UserToken found = token == null ? null : tokens.findByTokenHash(AuthService.hash(token)).orElse(null);
		if (found == null || found.getType() != type || !found.isUsable(clock.instant())) {
			// 만료·사용·위조를 구분하지 않음
			throw new NotFoundException("link", 0);
		}
		return found;
	}

	private static String normalizedEmail(String email) {
		try {
			return User.normalizeEmail(email);
		}
		catch (IllegalArgumentException ex) {
			throw InvalidRequestException.of(ex);
		}
	}

	private static void requireStrongPassword(String password) {
		if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
			throw new InvalidRequestException(InvalidValueException.Code.PASSWORD_TOO_SHORT,
					Map.of("min", String.valueOf(MIN_PASSWORD_LENGTH)),
					"password must be at least " + MIN_PASSWORD_LENGTH + " characters");
		}
	}

	/**
	 * @param token 링크에 담을 토큰 원문
	 */
	public record IssuedToken(String token, Instant expiresAt) {
	}

	public record InviteInfo(String email, Instant expiresAt) {
	}

	/** @param invitedBy 발급한 관리자의 이메일 (찾을 수 없으면 null) */
	public record PendingInvite(long id, String email, User.Role role, Instant createdAt, Instant expiresAt,
			String invitedBy) {

		static PendingInvite of(UserToken invite, User inviter) {
			return new PendingInvite(invite.getId(), invite.getEmail(), invite.getRole(), invite.getCreatedAt(),
					invite.getExpiresAt(), inviter == null ? null : inviter.getEmail());
		}

	}

	/** @param userId 비밀번호를 바꿀 사용자 ({@code PUT /api/users/{userId}/password}) */
	public record ResetInfo(long userId, String email, Instant expiresAt) {
	}

	public record UserSummary(long id, String email, String name, User.Role role, Instant createdAt) {

		static UserSummary of(User user) {
			return new UserSummary(user.getId(), user.getEmail(), user.getName(), user.getRole(), user.getCreatedAt());
		}

	}

}
