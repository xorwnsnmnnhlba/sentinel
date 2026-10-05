package com.sentinel.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 결과 원문 보관 정책.
 *
 * @param enabled 정리 배치 동작 여부
 * @param rawRetention 요청·응답 원문 보관 기간. 실행 시작 시각 기준
 * @param notificationRetention 알림 발송 이력 보관 기간. 발송 시각 기준
 * @param cron 정리 배치 실행 시각 (Spring cron, 초 포함 6자리). {@code sentinel.schedule.zone} 기준
 * @param batchSize 한 트랜잭션에서 정리할 실행 수. 장시간 잠금 방지
 */
@ConfigurationProperties("sentinel.retention")
record RetentionProperties(
		@DefaultValue("true") boolean enabled,
		@DefaultValue("30d") Duration rawRetention,
		@DefaultValue("90d") Duration notificationRetention,
		@DefaultValue("0 30 3 * * *") String cron,
		@DefaultValue("500") int batchSize) {

	public RetentionProperties {
		// 0 이하이면 방금 끝난 실행의 원문까지 지워지므로 기동 시 거부
		if (rawRetention.isNegative() || rawRetention.isZero()) {
			throw new IllegalArgumentException("sentinel.retention.raw-retention must be positive: " + rawRetention);
		}
		if (notificationRetention.isNegative() || notificationRetention.isZero()) {
			throw new IllegalArgumentException(
					"sentinel.retention.notification-retention must be positive: " + notificationRetention);
		}
		if (batchSize < 1) {
			throw new IllegalArgumentException("sentinel.retention.batch-size must be positive: " + batchSize);
		}
	}

}
