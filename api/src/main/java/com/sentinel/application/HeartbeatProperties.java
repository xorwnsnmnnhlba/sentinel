package com.sentinel.application;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 외부 생존 신호 ({@link Heartbeat})
 *
 * @param url 외부 감시 서비스의 신호 주소 (GET). 주소 자체가 자격증명이므로 환경변수로 주입하고 로그에 남기지 않음. 미지정 시 보내지 않음
 * @param timeout 연결·응답 제한 시간. 폴링을 오래 붙잡지 않도록 짧게
 */
@ConfigurationProperties("sentinel.heartbeat")
public record HeartbeatProperties(String url, @DefaultValue("5s") Duration timeout) {

	public HeartbeatProperties {
		url = url == null || url.isBlank() ? null : url.strip();
		// 잘못된 주소는 기동 시 거부. 메시지에 주소는 넣지 않음
		if (url != null && !isHttpUrl(url)) {
			throw new IllegalArgumentException("sentinel.heartbeat.url must be an absolute http(s) URL");
		}
		if (timeout.isNegative() || timeout.isZero()) {
			throw new IllegalArgumentException("sentinel.heartbeat.timeout must be positive: " + timeout);
		}
	}

	public boolean enabled() {
		return url != null;
	}

	private static boolean isHttpUrl(String value) {
		try {
			URI uri = URI.create(value);
			return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && uri.getHost() != null;
		}
		catch (IllegalArgumentException ex) {
			return false;
		}
	}

}
