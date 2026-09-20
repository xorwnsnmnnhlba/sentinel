package com.sentinel.application.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NotificationProperties.class)
class NotificationConfig {

	private static final Logger log = LoggerFactory.getLogger(NotificationConfig.class);

	/** Webhook 주소 지정 시 발송, 미지정 시 로그 기록. 주소는 자격증명이므로 로그에 남기지 않음 */
	@Bean
	Notifier notifier(NotificationProperties properties) {
		if (!properties.hasWebhook()) {
			log.info("Notifications are written to the log (sentinel.notification.webhook-url is not set)");
			return new LoggingNotifier();
		}
		log.info("Notifications are sent to the configured webhook");
		return new WebhookNotifier(properties.webhookUrl(), properties.connectTimeout(), properties.readTimeout());
	}

}
