package com.sentinel.application;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 수동 실행을 요청 스레드에서 끝까지 진행. 실행 API 응답에 완료된 결과가 담기므로 결과를 바로 검증하는 테스트용.
 * 백그라운드 진행 자체는 {@code ManualRunApiTest}에서 검증
 */
@TestConfiguration(proxyBeanMethods = false)
public class InlineBackgroundRunsConfiguration {

	@Bean
	@Primary
	BackgroundRuns inlineBackgroundRuns() {
		return Runnable::run;
	}

}
