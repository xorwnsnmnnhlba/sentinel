package com.sentinel.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.scheduling.support.CronExpression;

/**
 * 주간 요약 알림. 스케줄 폴링({@code sentinel.schedule.enabled})이 꺼져 있으면 함께 꺼짐
 *
 * @param enabled 발송 여부
 * @param cron 발송 시각 (Spring cron, 초 포함 6자리). {@code sentinel.schedule.zone} 기준. 요약 기간은 발송일 전날까지 7일
 */
@ConfigurationProperties("sentinel.digest")
public record DigestProperties(
		@DefaultValue("true") boolean enabled,
		@DefaultValue("0 0 9 * * MON") String cron) {

	public DigestProperties {
		// 잘못된 cron은 기동 시 거부 (발송 시각이 되어서야 알게 되지 않도록)
		if (!CronExpression.isValidExpression(cron)) {
			throw new IllegalArgumentException("sentinel.digest.cron is not a valid cron expression: " + cron);
		}
	}

	CronExpression cronExpression() {
		return CronExpression.parse(cron);
	}

}
