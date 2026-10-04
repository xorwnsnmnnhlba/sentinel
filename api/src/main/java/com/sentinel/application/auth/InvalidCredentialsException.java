package com.sentinel.application.auth;

/**
 * 로그인 실패. 계정 존재 여부를 알리지 않기 위해 사유를 구분하지 않음.
 */
public class InvalidCredentialsException extends RuntimeException {

	InvalidCredentialsException() {
		super("email or password is incorrect");
	}

}
