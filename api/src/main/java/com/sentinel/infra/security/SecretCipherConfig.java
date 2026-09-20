package com.sentinel.infra.security;

import com.sentinel.application.secret.SecretCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SecretProperties.class)
class SecretCipherConfig {

	private static final Logger log = LoggerFactory.getLogger(SecretCipherConfig.class);

	/** 키 미설정 시 시크릿 등록을 거부하는 구현 사용. 키 값은 로그에 남기지 않음 */
	@Bean
	SecretCipher secretCipher(SecretProperties properties) {
		if (!properties.hasKey()) {
			log.info("Secrets are disabled (sentinel.secret.key is not set)");
			return new DisabledSecretCipher();
		}
		log.info("Secrets are encrypted with AES-GCM");
		return new AesGcmSecretCipher(properties.key());
	}

}
