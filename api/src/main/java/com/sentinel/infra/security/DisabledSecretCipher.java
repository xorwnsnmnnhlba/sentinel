package com.sentinel.infra.security;

import com.sentinel.application.secret.SecretCipher;
import com.sentinel.application.secret.SecretKeyMissingException;

/**
 * 암호화 키 미설정 시 사용. 시크릿 저장·사용을 모두 거부하여 평문 저장을 막음.
 */
public class DisabledSecretCipher implements SecretCipher {

	@Override
	public String encrypt(String plainText) {
		throw new SecretKeyMissingException();
	}

	@Override
	public String decrypt(String cipherText) {
		throw new SecretKeyMissingException();
	}

	@Override
	public boolean isEnabled() {
		return false;
	}

}
