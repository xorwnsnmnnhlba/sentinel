package com.sentinel.application;

import java.time.Duration;
import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param enabled 폴링 스케줄러 동작 여부
 * @param zone cron 해석 시간대. 컨테이너 기본값 UTC 사용 시 9시간 어긋남 방지
 * @param pollInterval 실행 시각 도달 스케줄 조회 주기
 * @param gapThreshold 모니터링 공백 기준. 스케줄이 예정 시각보다 이 시간 넘게 늦게 실행되면 공백으로 기록·알림.
 * 폴링 주기와 평소 실행 시간보다 길게 설정
 */
@ConfigurationProperties("sentinel.schedule")
public record ScheduleProperties(
		@DefaultValue("true") boolean enabled,
		@DefaultValue("Asia/Seoul") ZoneId zone,
		@DefaultValue("60s") Duration pollInterval,
		@DefaultValue("10m") Duration gapThreshold) {
}
