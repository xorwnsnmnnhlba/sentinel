package com.sentinel.application.secret;

/**
 * 암호화 키 미설정 상태에서 시크릿을 저장·사용하려는 경우.
 */
public class SecretKeyMissingException extends RuntimeException {

	public SecretKeyMissingException() {
		super("secret key is not configured (sentinel.secret.key)");
	}

}
