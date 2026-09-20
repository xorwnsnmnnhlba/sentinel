package com.sentinel.infra.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param key Base64 인코딩한 AES 키 (16·24·32바이트). 미지정 시 시크릿 기능 비활성.
 * 환경변수로 주입하고 git·로그에 남기지 않음. 생성 예: {@code openssl rand -base64 32}
 */
@ConfigurationProperties("sentinel.secret")
public record SecretProperties(String key) {

	boolean hasKey() {
		return key != null && !key.isBlank();
	}

}
