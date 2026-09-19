package com.sentinel.application;

import java.time.Clock;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(ScheduleProperties.class)
@RequiredArgsConstructor
class SchedulingConfig implements SchedulingConfigurer {

	private final ScheduleProperties properties;

	private final SchedulePoller poller;

	@Override
	public void configureTasks(ScheduledTaskRegistrar registrar) {
		if (properties.enabled()) {
			registrar.addFixedDelayTask(poller::poll, properties.pollInterval());
		}
	}

	@Bean
	static Clock clock() {
		return Clock.systemUTC();
	}

}
