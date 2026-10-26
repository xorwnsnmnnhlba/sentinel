package com.sentinel.application;

import java.time.Duration;
import java.time.Instant;

import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.TriggerContext;

/**
 * 스케줄 폴링 시각. 폴링 주기의 경계(60초면 매분 0초) 직후에 실행하고, 매번 벽시계 기준으로 다음 경계를 다시 계산.
 * <p>
 * 이전 폴링이 끝난 뒤 주기만큼 기다리면(fixed delay) 폴링 안의 스위트 실행 시간만큼 주기가 밀려 분 경계를 넘을 때 매분 스케줄의
 * 한 회가 빠짐 (장시간 실행 점검에서 약 22분마다 1회). 폴링이 주기보다 길어지면 끝난 뒤의 다음 경계에 실행하고, 그사이 실행 시각이
 * 지난 스케줄은 그 폴링에서 1회 실행 (놓친 실행은 1회씩)
 */
final class AlignedPollTrigger implements Trigger {

	/**
	 * 경계에서 이만큼 뒤에 실행. 타이머가 벽시계보다 조금 일찍 깨어나도 경계 시각의 스케줄이 아직 실행 시각 전으로 보여 다음 주기로
	 * 밀리지 않도록
	 */
	static final Duration OFFSET = Duration.ofSeconds(1);

	private final Duration interval;

	AlignedPollTrigger(Duration interval) {
		if (interval.isNegative() || interval.isZero()) {
			throw new IllegalArgumentException("poll interval must be positive: " + interval);
		}
		this.interval = interval;
	}

	@Override
	public Instant nextExecution(TriggerContext context) {
		Instant lastCompletion = context.lastCompletion();
		return nextAfter(lastCompletion != null ? lastCompletion : context.getClock().instant(), interval);
	}

	/**
	 * {@code after}보다 뒤의 가장 가까운 "주기 경계 + {@link #OFFSET}"
	 */
	static Instant nextAfter(Instant after, Duration interval) {
		long intervalMillis = interval.toMillis();
		long shifted = after.minus(OFFSET).toEpochMilli();
		long boundary = Math.floorDiv(shifted, intervalMillis) * intervalMillis + intervalMillis;
		return Instant.ofEpochMilli(boundary).plus(OFFSET);
	}

}
