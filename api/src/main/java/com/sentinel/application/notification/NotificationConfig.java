package com.sentinel.application.notification;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class NotificationConfig {

	@Bean
	Notifier notifier() {
		return new LoggingNotifier();
	}

}
