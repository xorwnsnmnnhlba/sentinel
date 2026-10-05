package com.sentinel.application.notification;

import com.sentinel.domain.NotificationDelivery;

/**
 * 알림 발송 결과 기록 ({@link NotificationDeliveryRecorder}). 발송한 쪽은 결과만 넘기고, 기록 실패(DB 연결 끊김 등)는 구현이 삼킴
 * (DB 연결 실패 알림도 같은 경로로 보내므로 기록 때문에 발송이 실패하지 않아야 함)
 */
interface DeliveryLog {

	/** 기록하지 않음 (테스트용) */
	DeliveryLog NONE = (target, notification, delivery) -> {
	};

	void record(Target target, Notification notification, WebhookSender.Delivery delivery);

	/**
	 * 보낸 곳. 주소는 형식 판별에만 쓰고 남기지 않음
	 * @param projectId 프로젝트 Webhook이면 그 프로젝트, 기본 채널이면 null
	 */
	record Target(NotificationDelivery.Channel channel, Long projectId, NotificationDelivery.Format format) {

		/** 서버 기본 채널 Webhook */
		static Target defaultWebhook(String url) {
			return new Target(NotificationDelivery.Channel.DEFAULT, null, formatOf(url));
		}

		/** 기본 채널 주소 미설정 (서버 로그에만 기록) */
		static Target log() {
			return new Target(NotificationDelivery.Channel.DEFAULT, null, NotificationDelivery.Format.LOG);
		}

		static Target project(long projectId, String url) {
			return new Target(NotificationDelivery.Channel.PROJECT, projectId, formatOf(url));
		}

		private static NotificationDelivery.Format formatOf(String url) {
			return NotificationDelivery.Format.valueOf(WebhookPayload.formatOf(url).name());
		}

	}

}
