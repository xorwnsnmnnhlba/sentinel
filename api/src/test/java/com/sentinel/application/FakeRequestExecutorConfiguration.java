package com.sentinel.application;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class FakeRequestExecutorConfiguration {

	@Bean
	@Primary
	FakeRequestExecutor fakeRequestExecutor() {
		return new FakeRequestExecutor();
	}

}
