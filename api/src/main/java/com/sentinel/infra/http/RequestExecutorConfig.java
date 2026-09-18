package com.sentinel.infra.http;

import com.sentinel.runner.RequestExecutor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RunnerHttpProperties.class)
class RequestExecutorConfig {

	@Bean
	RequestExecutor requestExecutor(RunnerHttpProperties properties) {
		return new RestClientRequestExecutor(properties.connectTimeout(), properties.readTimeout());
	}

}
