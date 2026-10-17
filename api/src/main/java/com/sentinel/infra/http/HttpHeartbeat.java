package com.sentinel.infra.http;

import java.net.http.HttpClient;
import java.time.Duration;

import com.sentinel.application.Heartbeat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 신호 주소로 GET 요청. healthchecks.io·Uptime Kuma(푸시)·Better Stack(하트비트) 등 대부분 GET을 받음.
 * <p>
 * 실패는 처음 1회만 WARN, 다시 성공하면 INFO 1회 (매 주기 같은 경고가 쌓이지 않도록). 주소는 자격증명이므로 로그에 남기지 않음.
 * 스케줄러 단일 스레드에서만 호출
 */
public class HttpHeartbeat implements Heartbeat {

	private static final Logger log = LoggerFactory.getLogger(HttpHeartbeat.class);

	private final RestClient restClient;

	private final String url;

	private boolean failing;

	public HttpHeartbeat(String url, Duration timeout) {
		this.url = url;
		HttpClient httpClient = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(timeout)
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(timeout);
		this.restClient = RestClient.builder()
			.requestFactory(requestFactory)
			.defaultHeader("User-Agent", UserAgent.VALUE)
			.build();
	}

	@Override
	public void beat() {
		try {
			restClient.get().uri(url).retrieve().toBodilessEntity();
		}
		catch (RestClientException ex) {
			if (!failing) {
				log.warn("Heartbeat ping failed ({}). The external monitor will report Sentinel as down if this continues",
						HttpFailures.describe(ex));
			}
			failing = true;
			return;
		}
		if (failing) {
			log.info("Heartbeat ping recovered");
		}
		failing = false;
	}

}
