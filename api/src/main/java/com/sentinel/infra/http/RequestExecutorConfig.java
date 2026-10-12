package com.sentinel.infra.http;

import com.sentinel.runner.RequestExecutor;
import com.sentinel.runner.TransportRetry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ RunnerHttpProperties.class, RunnerRetryProperties.class })
class RequestExecutorConfig {

	@Bean
	RequestExecutor requestExecutor(RunnerHttpProperties properties) {
		return new RestClientRequestExecutor(properties.connectTimeout(), properties.readTimeout(),
				properties.totalTimeout(), (int) properties.maxResponseSize().toBytes());
	}

	@Bean
	TransportRetry transportRetry(RunnerRetryProperties properties) {
		return new TransportRetry(properties.maxRetries(), properties.delay(), Thread::sleep);
	}

}
