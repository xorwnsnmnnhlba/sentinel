package com.sentinel.application.notification;

/**
 * 알림 채널. 채널(사내 메신저 Webhook 등) 확정 시 구현체 추가 후 {@link NotificationConfig}의 빈 교체.
 * <p>
 * 구현체는 DB에 의존하지 않아야 함. DB 연결 실패 알림도 이 인터페이스로 발송.
 */
public interface Notifier {

	void send(Notification notification);

}
