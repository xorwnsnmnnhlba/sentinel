package com.sentinel.application.auth;

/**
 * 세션 없이 보호된 API를 호출한 경우. 401로 응답.
 */
public class UnauthenticatedException extends RuntimeException {

	public UnauthenticatedException() {
		super("authentication required");
	}

}
