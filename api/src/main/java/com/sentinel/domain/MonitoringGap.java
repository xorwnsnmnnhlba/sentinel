package com.sentinel.domain;

import java.time.Duration;
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
 * 모니터링 공백. 스케줄이 예정 시각보다 크게 늦게 실행된 기간 (Sentinel 중지·서버 절전·DB 연결 끊김 등으로 폴링이 멈춤).
 * <p>
 * 폴링이 재개될 때 늦은 스케줄의 가장 이른 예정 시각부터 재개 시각까지 기록. 공백이 끝난 뒤 기준 시간 안에 다시 시작된 공백은
 * 같은 공백으로 이어 붙임 (밀린 실행을 처리하느라 다음 폴링이 늦어진 경우 등)
 */
@Entity
@Table(name = "monitoring_gap", indexes = @Index(name = "idx_monitoring_gap_ended_at", columnList = "ended_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MonitoringGap extends BaseEntity {

	/** 늦은 스케줄 중 가장 이른 예정 시각 */
	@Column(nullable = false)
	private Instant startedAt;

	/** 폴링 재개 시각 */
	@Column(nullable = false)
	private Instant endedAt;

	/** 예정 시각에 실행되지 않은 스케줄 수. 이어 붙인 공백은 가장 많았던 수 */
	@Column(nullable = false)
	private int missedSchedules;

	/**
	 * @throws IllegalArgumentException 시작이 끝보다 늦음, 스케줄 수 1 미만
	 */
	public MonitoringGap(Instant startedAt, Instant endedAt, int missedSchedules) {
		this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
		this.endedAt = requireAfter(startedAt, endedAt);
		this.missedSchedules = requirePositive(missedSchedules);
	}

	/** 끝난 뒤 {@code tolerance} 안에 시작된 공백이면 이어진 공백 */
	public boolean isContinuedBy(Instant startedAt, Duration tolerance) {
		return !startedAt.isAfter(endedAt.plus(tolerance));
	}

	/**
	 * 이어진 공백을 합침. 시작은 더 이른 쪽, 끝은 더 늦은 쪽, 스케줄 수는 더 많은 쪽
	 * @throws IllegalArgumentException 시작이 끝보다 늦음, 스케줄 수 1 미만
	 */
	public void extend(Instant startedAt, Instant endedAt, int missedSchedules) {
		requireAfter(startedAt, endedAt);
		requirePositive(missedSchedules);
		if (startedAt.isBefore(this.startedAt)) {
			this.startedAt = startedAt;
		}
		if (endedAt.isAfter(this.endedAt)) {
			this.endedAt = endedAt;
		}
		this.missedSchedules = Math.max(this.missedSchedules, missedSchedules);
	}

	public Duration duration() {
		return Duration.between(startedAt, endedAt);
	}

	private static Instant requireAfter(Instant startedAt, Instant endedAt) {
		Objects.requireNonNull(endedAt, "endedAt");
		if (!endedAt.isAfter(startedAt)) {
			throw new IllegalArgumentException("endedAt must be after startedAt");
		}
		return endedAt;
	}

	private static int requirePositive(int missedSchedules) {
		if (missedSchedules < 1) {
			throw new IllegalArgumentException("missedSchedules must be at least 1");
		}
		return missedSchedules;
	}

}
