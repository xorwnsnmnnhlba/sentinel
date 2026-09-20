package com.sentinel.application.secret;

/**
 * 저장된 암호문을 복호화하지 못한 경우. 키 교체·손상 시 발생하며 메시지에 값을 포함하지 않음.
 */
public class SecretDecryptionException extends RuntimeException {

	public SecretDecryptionException(String message, Throwable cause) {
		super(message, cause);
	}

}
