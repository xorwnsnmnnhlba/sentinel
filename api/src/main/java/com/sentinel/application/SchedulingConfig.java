package com.sentinel.application;

import java.time.Clock;

import com.sentinel.infra.http.HttpHeartbeat;
import lombok.RequiredArgsConstructor;
import org.slf4j.LoggerFactory;
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
@EnableConfigurationProperties({ ScheduleProperties.class, RetentionProperties.class, DigestProperties.class,
		HeartbeatProperties.class, SlowdownProperties.class })
@RequiredArgsConstructor
class SchedulingConfig implements SchedulingConfigurer {

	private final ScheduleProperties properties;

	private final SchedulePoller poller;

	private final RetentionProperties retentionProperties;

	private final RawResultRetention retention;

	private final NotificationDeliveryService notificationDeliveries;

	private final DigestProperties digestProperties;

	private final WeeklyDigestSender digestSender;

	private final SlowdownProperties slowdownProperties;

	private final SlowdownDetector slowdownDetector;

	@Override
	public void configureTasks(ScheduledTaskRegistrar registrar) {
		if (properties.enabled()) {
			registrar.addFixedDelayTask(poller::poll, properties.pollInterval());
			// cron 작업 대신 주기 확인: 예정 시각에 꺼져 있었으면 켜진 뒤 발송 (스케줄과 같은 방식)
			if (digestProperties.enabled()) {
				registrar.addFixedDelayTask(digestSender::sendIfDue, properties.pollInterval());
			}
			// 놓친 확인은 다음 시각에 같은 기간으로 다시 보므로 cron 작업으로 충분
			if (slowdownProperties.enabled()) {
				registrar.addCronTask(new CronTask(slowdownDetector::check,
						new CronTrigger(slowdownProperties.cron(), properties.zone())));
			}
		}
		if (retentionProperties.enabled()) {
			// 스케줄 cron과 같은 시간대로 해석 (컨테이너 기본값 UTC 사용 시 새벽 정리가 낮에 실행되는 것 방지)
			registrar.addCronTask(new CronTask(retention::purgeExpired,
					new CronTrigger(retentionProperties.cron(), properties.zone())));
			// 원문 정리와 따로 등록 (한쪽이 실패해도 다른 쪽은 실행)
			registrar.addCronTask(new CronTask(notificationDeliveries::deleteExpired,
					new CronTrigger(retentionProperties.cron(), properties.zone())));
		}
	}

	/** 외부 생존 신호. 주소 미지정 시 보내지 않음. 주소는 자격증명이므로 로그에 남기지 않음 */
	@Bean
	static Heartbeat heartbeat(HeartbeatProperties properties) {
		if (!properties.enabled()) {
			return Heartbeat.NONE;
		}
		LoggerFactory.getLogger(SchedulingConfig.class).info("Heartbeat pings are sent to the configured URL on each poll");
		return new HttpHeartbeat(properties.url(), properties.timeout());
	}

	@Bean
	static Clock clock() {
		return Clock.systemUTC();
	}

}
