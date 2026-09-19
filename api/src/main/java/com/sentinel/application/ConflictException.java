package com.sentinel.application;

/**
 * 사용 중인 항목과의 충돌. 예: 같은 부모 아래 이름 중복
 */
public class ConflictException extends RuntimeException {

	public ConflictException(String message) {
		super(message);
	}

}
