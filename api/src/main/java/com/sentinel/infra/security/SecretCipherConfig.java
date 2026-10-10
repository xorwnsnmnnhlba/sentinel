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

	/**
	 * 키 미설정 시 시크릿 등록을 거부하는 구현 사용. 이전 키만 있으면 기동 실패 (새 키 없이 교체 불가).
	 * 키 값은 로그에 남기지 않음
	 */
	@Bean
	SecretCipher secretCipher(SecretProperties properties) {
		if (!properties.hasKey()) {
			if (properties.hasPreviousKey()) {
				throw new IllegalStateException(
						"sentinel.secret.previous-key is set without sentinel.secret.key; set the new key as well");
			}
			log.info("Secrets are disabled (sentinel.secret.key is not set)");
			return new DisabledSecretCipher();
		}
		if (!properties.hasPreviousKey()) {
			log.info("Secrets are encrypted with AES-GCM");
			return new AesGcmSecretCipher(properties.key());
		}
		if (properties.previousKey().strip().equals(properties.key().strip())) {
			log.warn("sentinel.secret.previous-key is the same as sentinel.secret.key and is ignored");
			return new AesGcmSecretCipher(properties.key());
		}
		log.info("Secrets are encrypted with AES-GCM (values saved with the previous key are re-encrypted)");
		return new AesGcmSecretCipher(properties.key(), properties.previousKey());
	}

}
