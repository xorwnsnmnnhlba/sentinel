package com.sentinel.runner;

import java.util.Map;

/**
 * 화면이 한국어 문장을 만들 수 있도록 종류(code)와 값(params)을 붙인 입력 값 오류. 메시지는 영어 그대로.
 * 판정 규칙·값 추출(runner)과 스케줄(domain)이 함께 쓰므로 아래 계층인 runner에 둠 (web/lib/input-error.ts)
 */
public class InvalidValueException extends IllegalArgumentException {

	private final Code code;

	private final Map<String, String> params;

	/** 오류 종류. 괄호 안은 params 이름 */
	public enum Code {
		/** JsonPath 문법 오류 (expression) */
		INVALID_JSON_PATH,
		/** 정규식 문법 오류 (expression) */
		INVALID_REGEX,
		/** cron 값이 5개가 아님 (cron) */
		CRON_FIELD_COUNT,
		/** cron의 한 칸이 잘못됨 (field: minute·hour·day·month·weekday, value) */
		CRON_INVALID_FIELD,
		/** 칸을 특정할 수 없는 cron 오류 (cron) */
		CRON_INVALID,
		/** 실행 시각이 오지 않는 cron. 예: 2월 31일 (cron) */
		CRON_NEVER_FIRES
	}

	public InvalidValueException(Code code, Map<String, String> params, String message, Throwable cause) {
		super(message, cause);
		this.code = code;
		this.params = Map.copyOf(params);
	}

	public Code code() {
		return code;
	}

	public Map<String, String> params() {
		return params;
	}

}
