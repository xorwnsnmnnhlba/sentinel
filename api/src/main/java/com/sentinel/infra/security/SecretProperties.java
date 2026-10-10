package com.sentinel.infra.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param key Base64 인코딩한 AES 키 (16·24·32바이트). 미지정 시 시크릿 기능 비활성.
 * 환경변수로 주입하고 git·로그에 남기지 않음. 생성 예: {@code openssl rand -base64 32}
 * @param previousKey 키 교체 시 이전 키 (선택). 이 키로 저장된 값도 복호화하고 기동 시 현재 키로 다시 암호화.
 * 재암호화를 확인한 뒤 제거
 */
@ConfigurationProperties("sentinel.secret")
record SecretProperties(String key, String previousKey) {

	boolean hasKey() {
		return key != null && !key.isBlank();
	}

	boolean hasPreviousKey() {
		return previousKey != null && !previousKey.isBlank();
	}

}
