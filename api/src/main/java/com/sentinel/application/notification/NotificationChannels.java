package com.sentinel.application.notification;

import java.util.List;

import com.sentinel.application.secret.SecretCipher;
import com.sentinel.application.secret.SecretDecryptionException;
import com.sentinel.application.secret.SecretKeyMissingException;
import com.sentinel.domain.Project;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 알림을 보낼 채널. 프로젝트에 Webhook이 있으면 그 주소, 없거나 읽을 수 없으면 서버 기본 채널 ({@link Notifier} 빈)
 * <p>
 * 읽을 수 없는 경우(암호화 키 변경·미설정)에도 알림을 잃지 않도록 기본 채널로 보내고 경고 로그
 */
@Component
@RequiredArgsConstructor
public class NotificationChannels {

	private static final Logger log = LoggerFactory.getLogger(NotificationChannels.class);

	private final Notifier defaultChannel;

	private final WebhookSender sender;

	private final SecretCipher cipher;

	private final DeliveryLog deliveries;

	/** 트랜잭션 안에서 호출 (프로젝트의 Webhook 암호문 조회). 발송은 트랜잭션 밖에서 */
	public Notifier forProject(Project project) {
		if (!project.hasWebhook()) {
			return defaultChannel;
		}
		String url;
		try {
			url = cipher.decrypt(project.getWebhookUrlCipher());
		}
		catch (SecretKeyMissingException | SecretDecryptionException ex) {
			log.warn("Webhook of project {} cannot be read ({}); sending to the default channel", project.getId(),
					ex.getClass().getSimpleName());
			return defaultChannel;
		}
		long projectId = project.getId();
		return notification -> {
			WebhookSender.Delivery delivery = sender.deliver(url, notification);
			if (!delivery.delivered()) {
				log.warn("Notification delivery to the webhook of project {} failed: {} ({})", projectId,
						notification.title(), delivery.describe());
			}
			deliveries.record(DeliveryLog.Target.project(projectId, url), notification, delivery);
		};
	}

	/**
	 * 서버 기본 채널로 연결 확인 알림. 실제 알림과 같은 경로로 보내고 발송 이력에 기록
	 * @param requester 보낸 사람 (이메일)
	 * @throws IllegalStateException 기본 채널 주소 미설정 (알림이 로그에만 남는 상태)
	 */
	public WebhookSender.Delivery sendDefaultTest(String requester) {
		if (!(defaultChannel instanceof WebhookNotifier webhook)) {
			throw new IllegalStateException("default notification channel is not configured");
		}
		return webhook.deliver(new Notification("[Sentinel] 기본 알림 채널 연결 확인",
				List.of("이 채널로 Webhook을 지정하지 않은 프로젝트의 실패·복구 알림과 DB 연결 실패·모니터링 공백·주간 요약을 보냅니다",
						"보낸 사람: " + requester)));
	}

	/**
	 * 연결 확인용 알림. 프로젝트 Webhook으로만 보냄 (기본 채널로 대신 보내지 않음)
	 * @param requester 보낸 사람 (이메일)
	 * @throws IllegalStateException Webhook 미지정
	 * @throws SecretKeyMissingException 암호화 키 미설정
	 * @throws SecretDecryptionException 키 불일치·손상
	 */
	public WebhookSender.Delivery sendTest(Project project, String requester) {
		if (!project.hasWebhook()) {
			throw new IllegalStateException("project " + project.getId() + " has no webhook");
		}
		String url = cipher.decrypt(project.getWebhookUrlCipher());
		Notification notification = new Notification("[Sentinel] " + project.getName() + " 알림 연결 확인",
				List.of("이 채널로 " + project.getName() + " 프로젝트의 스케줄 실행 실패·복구 알림을 보냅니다",
						"보낸 사람: " + requester));
		WebhookSender.Delivery delivery = sender.deliver(url, notification);
		deliveries.record(DeliveryLog.Target.project(project.getId(), url), notification, delivery);
		return delivery;
	}

}
