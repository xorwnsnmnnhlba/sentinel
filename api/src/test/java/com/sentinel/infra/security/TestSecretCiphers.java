package com.sentinel.infra.security;

import com.sentinel.application.secret.SecretCipher;

/**
 * 다른 패키지 테스트에서 특정 키의 암호문을 만들 때 사용 (키 교체 전 저장된 값 재현)
 */
public final class TestSecretCiphers {

	private TestSecretCiphers() {
	}

	public static SecretCipher withKey(String base64Key) {
		return new AesGcmSecretCipher(base64Key);
	}

}
