package com.sentinel.application.notification;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

import com.sentinel.infra.http.HttpFailures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 수신 Webhook으로 알림 발송. 본문은 {@code {"text": "..."}} 한 필드로, Slack·Teams 수신 Webhook에 그대로 사용 가능.
 * <p>
 * 발송 실패는 예외로 전파하지 않고 경고 로그만 남김. 알림 때문에 실행·폴링이 중단되지 않아야 함.
 * Webhook 주소는 자격증명이므로 로그에 남기지 않음.
 */
public class WebhookNotifier implements Notifier {

	private static final Logger log = LoggerFactory.getLogger(WebhookNotifier.class);

	private final RestClient restClient;

	private final String url;

	public WebhookNotifier(String url, Duration connectTimeout, Duration readTimeout) {
		this.url = url;
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
		try {
			restClient.post()
				.uri(url)
				.contentType(MediaType.APPLICATION_JSON)
				.body(Map.of("text", notification.text()))
				.retrieve()
				.toBodilessEntity();
		}
		catch (RestClientException ex) {
			// 예외 메시지에는 주소가 들어 있으므로 상태 코드·실패 종류만
			log.warn("Notification delivery failed: {} ({})", notification.title(), HttpFailures.describe(ex));
		}
	}

}
