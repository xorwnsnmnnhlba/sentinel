package com.sentinel.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.scheduling.support.CronExpression;

/**
 * 응답시간 악화 알림 ({@link SlowdownDetector}). 스케줄 폴링({@code sentinel.schedule.enabled})이 꺼져 있으면 함께 꺼짐
 *
 * @param enabled 확인 여부
 * @param cron 확인 시각 (Spring cron, 초 포함 6자리). {@code sentinel.schedule.zone} 기준. 기본 매시 5분
 * @param increaseRatio 악화 기준 비율. 0.5면 최근 p95가 비교 p95의 1.5배 이상
 * @param minIncrease 악화 기준 최소 증가폭. 비율과 함께 만족해야 함 (20ms→40ms처럼 빨라서 의미 없는 두 배 제외)
 * @param recentWindow 최근 기간 (지금까지)
 * @param baselineWindow 비교 기간 (최근 기간 바로 앞)
 * @param minSamples 두 기간 각각 필요한 최소 응답 수. 표본이 적은 p95는 흔들림이 커서 제외
 */
@ConfigurationProperties("sentinel.slowdown")
record SlowdownProperties(
		@DefaultValue("true") boolean enabled,
		@DefaultValue("0 5 * * * *") String cron,
		@DefaultValue("0.5") double increaseRatio,
		@DefaultValue("100ms") Duration minIncrease,
		@DefaultValue("24h") Duration recentWindow,
		@DefaultValue("7d") Duration baselineWindow,
		@DefaultValue("5") int minSamples) {

	SlowdownProperties {
		if (!CronExpression.isValidExpression(cron)) {
			throw new IllegalArgumentException("sentinel.slowdown.cron is not a valid cron expression: " + cron);
		}
		if (!(increaseRatio > 0)) {
			throw new IllegalArgumentException("sentinel.slowdown.increase-ratio must be positive: " + increaseRatio);
		}
		if (minIncrease.isNegative()) {
			throw new IllegalArgumentException("sentinel.slowdown.min-increase must not be negative: " + minIncrease);
		}
		if (!recentWindow.isPositive() || !baselineWindow.isPositive()) {
			throw new IllegalArgumentException("sentinel.slowdown windows must be positive");
		}
		if (minSamples < 1) {
			throw new IllegalArgumentException("sentinel.slowdown.min-samples must be at least 1: " + minSamples);
		}
	}

	/** 악화: 비율과 증가폭 모두 기준 이상 */
	boolean isSlow(long baselineP95, long recentP95) {
		return recentP95 >= baselineP95 * (1 + increaseRatio) && recentP95 - baselineP95 >= minIncrease.toMillis();
	}

	/**
	 * 회복: 감지 당시 비교 p95 대비 기준의 절반 아래로 돌아옴 (비율 또는 증가폭).
	 * 기준선에서 오르내리며 악화·회복 알림이 번갈아 오지 않도록 회복 기준을 낮춤
	 */
	boolean isRecovered(long baselineP95, long recentP95) {
		return recentP95 < baselineP95 * (1 + increaseRatio / 2)
				|| recentP95 - baselineP95 < minIncrease.toMillis() / 2;
	}

}
