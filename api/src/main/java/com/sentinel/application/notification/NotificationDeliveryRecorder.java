package com.sentinel.application.notification;

import java.time.Clock;

import com.sentinel.application.Transactions;
import com.sentinel.domain.NotificationDelivery;
import com.sentinel.infra.persistence.NotificationDeliveryRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 알림 발송 이력 저장. 호출한 쪽 트랜잭션과 별도로 커밋하고, 저장 실패는 경고 로그만 (발송은 이미 끝났으므로 알림에는 영향 없음)
 */
@Component
@RequiredArgsConstructor
class NotificationDeliveryRecorder implements DeliveryLog {

	private static final Logger log = LoggerFactory.getLogger(NotificationDeliveryRecorder.class);

	private final NotificationDeliveryRepository deliveries;

	private final Transactions transactions;

	private final Clock clock;

	@Override
	public Long record(Target target, Notification notification, WebhookSender.Delivery delivery) {
		try {
			return transactions.writeSeparately(status -> deliveries.save(new NotificationDelivery(clock.instant(),
					target.channel(), target.projectId(), target.format(), notification.title(),
					NotificationDelivery.Tone.valueOf(notification.tone().name()), delivery.delivered(),
					delivery.statusCode(), delivery.error())))
				.getId();
		}
		catch (RuntimeException ex) {
			log.warn("Failed to record notification delivery: {} ({})", notification.title(),
					ex.getClass().getSimpleName());
			return null;
		}
	}

}
