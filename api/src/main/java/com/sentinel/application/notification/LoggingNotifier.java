package com.sentinel.application.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 채널 확정 전 임시 구현. 알림 내용을 WARN 로그로 기록.
 */
class LoggingNotifier implements Notifier {

	private static final Logger log = LoggerFactory.getLogger(LoggingNotifier.class);

	private final DeliveryLog deliveries;

	LoggingNotifier(DeliveryLog deliveries) {
		this.deliveries = deliveries;
	}

	/** 발송 이력에는 "로그에 기록"으로 남김 (주소 미설정으로 채널에 가지 않았음을 화면에서 확인) */
	@Override
	public void send(Notification notification) {
		log.warn("Notification\n{}", notification.text());
		deliveries.record(DeliveryLog.Target.log(), notification, new WebhookSender.Delivery(true, null, null));
	}

}
