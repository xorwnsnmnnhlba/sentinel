package com.sentinel.application.notification;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 알림 채널 설정. webhookUrl 미지정 시 로그 알림 사용.
 *
 * @param webhookUrl 수신 Webhook 주소. 자격증명에 해당하므로 환경변수로 주입하고 로그에 남기지 않음
 * @param webBaseUrl 결과 화면 주소. 지정 시 실패 알림에 실행 결과 링크 추가 (예: http://sentinel.example.com)
 * @param connectTimeout 연결 제한 시간
 * @param readTimeout 응답 대기 제한 시간. 알림이 실행·폴링을 지연시키지 않도록 짧게 유지
 * @param repeatInterval 같은 케이스가 계속 실패하는 동안 실패 알림을 다시 보내는 간격. 새로 실패·복구는 바로 발송. 0이면 실행마다 발송
 */
@ConfigurationProperties("sentinel.notification")
public record NotificationProperties(
		String webhookUrl,
		String webBaseUrl,
		@DefaultValue("3s") Duration connectTimeout,
		@DefaultValue("5s") Duration readTimeout,
		@DefaultValue("1h") Duration repeatInterval) {

	public NotificationProperties {
		if (repeatInterval.isNegative()) {
			throw new IllegalArgumentException("sentinel.notification.repeat-interval must not be negative: "
					+ repeatInterval);
		}
		webBaseUrl = webBaseUrl == null ? null : webBaseUrl.strip().replaceAll("/+$", "");
	}

	boolean hasWebhook() {
		return webhookUrl != null && !webhookUrl.isBlank();
	}

	/** 대시보드 주소. webBaseUrl 미지정 시 null */
	public String dashboardUrl() {
		return webBaseUrl == null || webBaseUrl.isEmpty() ? null : webBaseUrl + "/";
	}

	/** 실행 결과 화면 주소. webBaseUrl 미지정 시 null */
	public String runUrl(long runId) {
		return webBaseUrl == null || webBaseUrl.isEmpty() ? null : webBaseUrl + "/runs/" + runId;
	}

}
