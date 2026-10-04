package com.sentinel.domain;

import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 응답시간 악화. 케이스·환경의 최근 p95가 비교 기간보다 기준 이상 나빠진 상태. 다시 기준 아래로 돌아오면 닫음
 * <p>
 * 회복은 감지 당시 비교 기간 p95({@code baselineP95}) 기준. 비교 기간이 지나며 느린 값을 흡수해도 회복으로 보지 않음
 */
@Entity
@Table(name = "slowdown_alert",
		indexes = @Index(name = "idx_slowdown_alert_open", columnList = "resolved_at, test_case_id, environment_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SlowdownAlert extends BaseEntity {

	@Column(nullable = false)
	private Long testCaseId;

	@Column(nullable = false)
	private Long environmentId;

	/** 감지 당시 비교 기간 p95 (ms) */
	@Column(nullable = false)
	private long baselineP95;

	/** 감지 당시 최근 기간 p95 (ms) */
	@Column(nullable = false)
	private long detectedP95;

	@Column(nullable = false)
	private Instant detectedAt;

	/** 회복 시각. null이면 아직 느림 */
	private Instant resolvedAt;

	/** 회복 당시 최근 기간 p95 (ms) */
	private Long resolvedP95;

	/**
	 * @throws IllegalArgumentException 비교 p95가 0 이하, 최근 p95가 비교보다 빠름
	 */
	public SlowdownAlert(long testCaseId, long environmentId, long baselineP95, long detectedP95, Instant detectedAt) {
		if (baselineP95 <= 0) {
			throw new IllegalArgumentException("baselineP95 must be positive");
		}
		if (detectedP95 <= baselineP95) {
			throw new IllegalArgumentException("detectedP95 must be slower than baselineP95");
		}
		this.testCaseId = testCaseId;
		this.environmentId = environmentId;
		this.baselineP95 = baselineP95;
		this.detectedP95 = detectedP95;
		this.detectedAt = Objects.requireNonNull(detectedAt, "detectedAt");
	}

	public boolean isOpen() {
		return resolvedAt == null;
	}

	/** @throws IllegalStateException 이미 회복 */
	public void resolve(long currentP95, Instant now) {
		if (!isOpen()) {
			throw new IllegalStateException("slowdown alert " + getId() + " is already resolved");
		}
		this.resolvedP95 = currentP95;
		this.resolvedAt = Objects.requireNonNull(now, "now");
	}

}
