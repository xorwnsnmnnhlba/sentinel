package com.sentinel.infra.http;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * @param connectTimeout 연결 제한 시간
 * @param readTimeout 응답을 기다리는 제한 시간. 읽을 때마다 다시 잼 (1바이트씩 늦게 오면 끝나지 않음)
 * @param totalTimeout 응답 본문을 다 받을 때까지의 전체 제한 시간. 넘으면 연결을 끊고 타임아웃 처리
 * @param maxResponseSize 받는 응답 본문의 최대 크기. 넘는 부분은 받지 않고 연결을 끊음 (메모리 보호)
 */
@ConfigurationProperties("sentinel.runner.http")
record RunnerHttpProperties(
		@DefaultValue("5s") Duration connectTimeout,
		@DefaultValue("30s") Duration readTimeout,
		@DefaultValue("2m") Duration totalTimeout,
		@DefaultValue("10MB") DataSize maxResponseSize) {

	RunnerHttpProperties {
		if (totalTimeout.isNegative() || totalTimeout.isZero()) {
			throw new IllegalArgumentException("sentinel.runner.http.total-timeout must be positive: " + totalTimeout);
		}
		if (maxResponseSize.toBytes() <= 0 || maxResponseSize.toBytes() > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("sentinel.runner.http.max-response-size must be between 1B and 2GB: "
					+ maxResponseSize);
		}
	}

}
