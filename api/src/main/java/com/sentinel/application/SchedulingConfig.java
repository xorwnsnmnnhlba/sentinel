package com.sentinel.application;

import java.time.Clock;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties({ ScheduleProperties.class, RetentionProperties.class, DigestProperties.class })
@RequiredArgsConstructor
class SchedulingConfig implements SchedulingConfigurer {

	private final ScheduleProperties properties;

	private final SchedulePoller poller;

	private final RetentionProperties retentionProperties;

	private final RawResultRetention retention;

	private final DigestProperties digestProperties;

	private final WeeklyDigestSender digestSender;

	@Override
	public void configureTasks(ScheduledTaskRegistrar registrar) {
		if (properties.enabled()) {
			registrar.addFixedDelayTask(poller::poll, properties.pollInterval());
			// cron 작업 대신 주기 확인: 예정 시각에 꺼져 있었으면 켜진 뒤 발송 (스케줄과 같은 방식)
			if (digestProperties.enabled()) {
				registrar.addFixedDelayTask(digestSender::sendIfDue, properties.pollInterval());
			}
		}
		if (retentionProperties.enabled()) {
			// 스케줄 cron과 같은 시간대로 해석 (컨테이너 기본값 UTC 사용 시 새벽 정리가 낮에 실행되는 것 방지)
			registrar.addCronTask(new CronTask(retention::purgeExpired,
					new CronTrigger(retentionProperties.cron(), properties.zone())));
		}
	}

	@Bean
	static Clock clock() {
		return Clock.systemUTC();
	}

}
