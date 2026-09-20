package com.sentinel.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 로그인 세션. 토큰 원문은 로그인 응답에만 전달하고 저장은 해시로만 함(DB 유출 시 세션 탈취 방지).
 * <p>
 * 만료는 절대 시각으로 관리하며 사용할 때마다 마지막 사용 시각 기록. 로그아웃은 행 삭제.
 */
@Entity
@Table(name = "user_session", indexes = @Index(name = "idx_user_session_user", columnList = "user_id"),
		uniqueConstraints = @UniqueConstraint(name = "uk_user_session_token", columnNames = "token_hash"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserSession extends BaseEntity {

	@Column(nullable = false, length = 100)
	private String tokenHash;

	@Column(nullable = false)
	private Long userId;

	@Column(nullable = false)
	private Instant expiresAt;

	@Column(nullable = false)
	private Instant lastUsedAt;

	public UserSession(String tokenHash, Long userId, Instant now, Duration validity) {
		this.tokenHash = Preconditions.requireText(tokenHash, "tokenHash", 100);
		this.userId = Objects.requireNonNull(userId, "userId");
		this.lastUsedAt = Objects.requireNonNull(now, "now");
		this.expiresAt = now.plus(validity);
	}

	public boolean isExpired(Instant now) {
		return !now.isBefore(expiresAt);
	}

	/** 사용 시각 갱신. 만료 시각은 연장하지 않음(고정 만료) */
	public void markUsed(Instant now) {
		this.lastUsedAt = now;
	}

}
