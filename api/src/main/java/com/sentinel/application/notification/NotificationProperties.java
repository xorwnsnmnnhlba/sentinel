package com.sentinel.application.notification;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 알림 채널 설정. webhookUrl 미지정 시 로그 알림 사용.
 *
 * @param webhookUrl 수신 Webhook 주소. 자격증명에 해당하므로 환경변수로 주입하고 로그에 남기지 않음
 * @param connectTimeout 연결 제한 시간
 * @param readTimeout 응답 대기 제한 시간. 알림이 실행·폴링을 지연시키지 않도록 짧게 유지
 */
@ConfigurationProperties("sentinel.notification")
public record NotificationProperties(
		String webhookUrl,
		@DefaultValue("3s") Duration connectTimeout,
		@DefaultValue("5s") Duration readTimeout) {

	boolean hasWebhook() {
		return webhookUrl != null && !webhookUrl.isBlank();
	}

}
