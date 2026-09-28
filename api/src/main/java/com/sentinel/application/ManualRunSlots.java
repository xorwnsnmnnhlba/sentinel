package com.sentinel.application;

import java.util.concurrent.Semaphore;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 동시 수동 실행 수 제한. 실행을 만들기 전에 자리를 잡고 실행이 끝나면(또는 준비가 실패하면) 반납
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ManualRunProperties.class)
class ManualRunSlots {

	private final int maxConcurrent;

	private final Semaphore permits;

	ManualRunSlots(ManualRunProperties properties) {
		this.maxConcurrent = properties.maxConcurrent();
		this.permits = new Semaphore(maxConcurrent);
	}

	/** @throws TooManyRunsException 남은 자리 없음 */
	void acquire() {
		if (!permits.tryAcquire()) {
			throw new TooManyRunsException(maxConcurrent);
		}
	}

	void release() {
		permits.release();
	}

}
