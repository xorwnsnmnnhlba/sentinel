package com.sentinel.infra.http;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("sentinel.runner.http")
record RunnerHttpProperties(
		@DefaultValue("5s") Duration connectTimeout,
		@DefaultValue("30s") Duration readTimeout) {
}
