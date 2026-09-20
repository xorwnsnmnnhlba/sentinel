package com.sentinel.application.auth;

/**
 * 권한이 없는 요청. 관리자 전용 기능을 일반 사용자가 호출한 경우 403으로 응답.
 */
public class ForbiddenException extends RuntimeException {

	public ForbiddenException(String message) {
		super(message);
	}

}
