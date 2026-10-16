package com.sentinel.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 초대·비밀번호 재설정 링크의 1회용 토큰. 원문은 발급 시에만 전달하고 해시로만 보관.
 * <p>
 * 사용하면 {@code usedAt}을 기록하여 재사용을 막고, 만료 시각이 지나면 사용 불가.
 * 사용 기록은 조건부 갱신({@code UserTokenRepository.markUsed})으로 하여 같은 링크를 동시에 써도 한 번만 성공
 */
@Entity
@Table(name = "user_token", indexes = @Index(name = "idx_user_token_user", columnList = "user_id"),
		uniqueConstraints = @UniqueConstraint(name = "uk_user_token_hash", columnNames = "token_hash"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserToken extends BaseEntity {

	@Column(nullable = false, length = 100)
	private String tokenHash;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Type type;

	/** 초대 대상 이메일. 재설정 토큰은 null */
	@Column(length = 255)
	private String email;

	@Enumerated(EnumType.STRING)
	@Column(length = 20)
	private User.Role role;

	/** 재설정 대상 사용자. 초대 토큰은 null */
	private Long userId;

	@Column(nullable = false)
	private Instant expiresAt;

	private Instant usedAt;

	@Column(nullable = false)
	private Long createdBy;

	private UserToken(String tokenHash, Type type, Instant now, Duration validity, Long createdBy) {
		this.tokenHash = Preconditions.requireText(tokenHash, "tokenHash", 100);
		this.type = Objects.requireNonNull(type, "type");
		this.expiresAt = Objects.requireNonNull(now, "now").plus(validity);
		this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
	}

	public static UserToken invite(String tokenHash, String email, User.Role role, Instant now, Duration validity,
			long createdBy) {
		UserToken token = new UserToken(tokenHash, Type.INVITE, now, validity, createdBy);
		token.email = User.normalizeEmail(email);
		token.role = role == null ? User.Role.MEMBER : role;
		return token;
	}

	public static UserToken passwordReset(String tokenHash, long userId, Instant now, Duration validity,
			long createdBy) {
		UserToken token = new UserToken(tokenHash, Type.PASSWORD_RESET, now, validity, createdBy);
		token.userId = userId;
		return token;
	}

	public boolean isUsable(Instant now) {
		return usedAt == null && now.isBefore(expiresAt);
	}

	public enum Type {

		/** 새 계정 생성용 */
		INVITE,
		/** 기존 계정의 비밀번호 재설정용 */
		PASSWORD_RESET

	}

}
