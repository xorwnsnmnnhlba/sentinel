package com.sentinel.infra.http;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 응답을 받지 못한 전송의 재시도 ({@link com.sentinel.runner.TransportRetry})
 *
 * @param maxRetries 케이스당 다시 보내는 최대 횟수 (0~3). 0이면 재시도하지 않음
 * @param delay 다시 보내기 전 대기
 */
@ConfigurationProperties("sentinel.runner.retry")
record RunnerRetryProperties(@DefaultValue("1") int maxRetries, @DefaultValue("1s") Duration delay) {
}
