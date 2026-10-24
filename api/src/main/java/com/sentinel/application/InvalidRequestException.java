package com.sentinel.application;

import java.util.Map;

import com.sentinel.runner.InvalidValueException;

/**
 * 잘못된 요청 (400). code가 있으면 응답에 code·params를 함께 담아 화면이 한국어 문장을 만들고, 없으면 메시지(영어)만
 */
public class InvalidRequestException extends RuntimeException {

	private final String code;

	private final Map<String, String> params;

	public InvalidRequestException(String message) {
		this(message, null, Map.of());
	}

	protected InvalidRequestException(String message, String code, Map<String, String> params) {
		super(message);
		this.code = code;
		this.params = Map.copyOf(params);
	}

	/** 도메인 검증 오류를 400으로. 종류가 붙은 오류({@link InvalidValueException})는 code·params 유지 */
	public static InvalidRequestException of(IllegalArgumentException ex) {
		return ex instanceof InvalidValueException coded
				? new InvalidRequestException(ex.getMessage(), coded.code().name(), coded.params())
				: new InvalidRequestException(ex.getMessage());
	}

	/** 화면용 오류 종류. 없으면 null */
	public String code() {
		return code;
	}

	public Map<String, String> params() {
		return params;
	}

}
