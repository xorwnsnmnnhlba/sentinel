package com.sentinel.application.auth;

import java.time.Duration;

/**
 * 반복된 로그인 실패로 잠긴 상태. 계정 존재 여부와 무관하게 같은 문구
 */
public class TooManyLoginAttemptsException extends RuntimeException {

	private final Duration retryAfter;

	public TooManyLoginAttemptsException(Duration retryAfter) {
		super("too many failed login attempts; try again later");
		this.retryAfter = retryAfter;
	}

	/** 다시 시도할 수 있을 때까지 남은 시간 */
	public Duration retryAfter() {
		return retryAfter;
	}

}
