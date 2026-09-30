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
 * CI 등 자동화용 API 토큰. 발급한 사용자로 실행을 시작하고 결과를 조회하는 것만 허용.
 * <p>
 * 원문은 발급 응답에만 전달하고 해시로만 저장 (DB 유출 시 토큰 탈취 방지). 만료는 절대 시각, 폐기는 {@code revokedAt}
 */
@Entity
@Table(name = "access_token", indexes = @Index(name = "idx_access_token_user", columnList = "user_id"),
		uniqueConstraints = @UniqueConstraint(name = "uk_access_token_hash", columnNames = "token_hash"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AccessToken extends BaseEntity {

	public static final int MAX_NAME_LENGTH = 100;

	@Column(nullable = false)
	private Long userId;

	/** 용도 (예: GitHub Actions 배포 확인) */
	@Column(nullable = false, length = MAX_NAME_LENGTH)
	private String name;

	@Column(nullable = false, length = 100)
	private String tokenHash;

	/** 목록에서 토큰을 구분하는 앞부분 (예: snt_Ab12). 나머지는 보관하지 않음 */
	@Column(nullable = false, length = 12)
	private String tokenPrefix;

	@Column(nullable = false)
	private Instant expiresAt;

	private Instant lastUsedAt;

	private Instant revokedAt;

	public AccessToken(long userId, String name, String tokenHash, String tokenPrefix, Instant now, Duration validity) {
		this.userId = userId;
		this.name = Preconditions.requireText(name, "name", MAX_NAME_LENGTH);
		this.tokenHash = Preconditions.requireText(tokenHash, "tokenHash", 100);
		this.tokenPrefix = Preconditions.requireText(tokenPrefix, "tokenPrefix", 12);
		this.expiresAt = Objects.requireNonNull(now, "now").plus(validity);
	}

	/** 만료·폐기되지 않음 */
	public boolean isUsable(Instant now) {
		return revokedAt == null && now.isBefore(expiresAt);
	}

	public void markUsed(Instant now) {
		this.lastUsedAt = Objects.requireNonNull(now, "now");
	}

	/** @throws IllegalStateException 이미 폐기됨 */
	public void revoke(Instant now) {
		if (revokedAt != null) {
			throw new IllegalStateException("access token " + getId() + " is already revoked");
		}
		this.revokedAt = Objects.requireNonNull(now, "now");
	}

}
