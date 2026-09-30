package com.sentinel.infra.security;

import com.sentinel.application.auth.AuthProperties;
import com.sentinel.application.auth.AccessTokenService;
import com.sentinel.application.auth.AuthService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthProperties.class)
class AuthConfig {

	/** 다른 필터보다 먼저 실행하여 인증되지 않은 요청이 컨트롤러에 도달하지 않도록 함 */
	@Bean
	FilterRegistrationBean<AuthFilter> authFilter(AuthService authService, AccessTokenService accessTokens) {
		FilterRegistrationBean<AuthFilter> registration = new FilterRegistrationBean<>(
				new AuthFilter(authService, accessTokens));
		registration.addUrlPatterns("/api/*");
		registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
		return registration;
	}

}
