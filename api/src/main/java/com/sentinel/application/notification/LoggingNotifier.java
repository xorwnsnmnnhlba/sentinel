package com.sentinel.application.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 채널 확정 전 임시 구현. 알림 내용을 WARN 로그로 기록.
 */
class LoggingNotifier implements Notifier {

	private static final Logger log = LoggerFactory.getLogger(LoggingNotifier.class);

	@Override
	public void send(Notification notification) {
		log.warn("Notification\n{}", notification.text());
	}

}
