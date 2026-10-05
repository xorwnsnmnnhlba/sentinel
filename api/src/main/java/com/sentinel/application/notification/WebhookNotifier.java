package com.sentinel.application.notification;

import java.net.http.HttpClient;
import java.time.Duration;

import com.sentinel.infra.http.HttpFailures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * 수신 Webhook으로 알림 발송. 본문은 {@code {"text": "..."}} 한 필드로, Slack·Teams 수신 Webhook에 그대로 사용 가능.
 * <p>
 * 발송 실패는 예외로 전파하지 않고 경고 로그만 남김. 알림 때문에 실행·폴링이 중단되지 않아야 함.
 * Webhook 주소는 자격증명이므로 로그에 남기지 않음. 발송 결과는 발송 이력({@link DeliveryLog})에 기록
 */
class WebhookNotifier implements Notifier {

	private static final Logger log = LoggerFactory.getLogger(WebhookNotifier.class);

	private final RestClient restClient;

	private final String url;

	private final DeliveryLog deliveries;

	WebhookNotifier(String url, Duration connectTimeout, Duration readTimeout, DeliveryLog deliveries) {
		this.url = url;
		this.deliveries = deliveries;
		// 기본값 HTTP/2는 http:// 요청에 h2c 업그레이드를 시도하여 서버에 따라 본문 전송 실패
		HttpClient httpClient = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(connectTimeout)
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(readTimeout);
		this.restClient = RestClient.builder().requestFactory(requestFactory).build();
	}

	@Override
	public void send(Notification notification) {
		WebhookSender.Delivery delivery;
		try {
			int status = restClient.post()
				.uri(url)
				.contentType(MediaType.APPLICATION_JSON)
				.body(WebhookPayload.of(url, notification))
				.retrieve()
				.toBodilessEntity()
				.getStatusCode()
				.value();
			delivery = new WebhookSender.Delivery(true, status, null);
		}
		catch (RestClientException ex) {
			// 예외 메시지에는 주소가 들어 있으므로 상태 코드·실패 종류만
			log.warn("Notification delivery failed: {} ({})", notification.title(), HttpFailures.describe(ex));
			delivery = ex instanceof RestClientResponseException response
					? new WebhookSender.Delivery(false, response.getStatusCode().value(), null)
					: new WebhookSender.Delivery(false, null, HttpFailures.reason(ex));
		}
		deliveries.record(DeliveryLog.Target.defaultWebhook(url), notification, delivery);
	}

}
