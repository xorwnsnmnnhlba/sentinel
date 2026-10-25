package com.sentinel.runner;

import java.util.Map;

/**
 * 화면이 한국어 문장을 만들 수 있도록 종류(code)와 값(params)을 붙인 입력 값 오류. 메시지는 영어 그대로.
 * 판정 규칙·값 추출(runner)과 도메인 검증(domain)이 함께 쓰므로 아래 계층인 runner에 둠 (web/lib/input-error.ts).
 * 400 응답의 code 목록도 겸함 (서비스가 직접 내는 시크릿 오류 포함)
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
		CRON_NEVER_FIRES,
		/** 빈 값 (target: 항목 이름. 예: name, baseUrl, secret name) */
		REQUIRED,
		/** 길이 초과 (target, max) */
		TOO_LONG,
		/** URI로 읽을 수 없는 기본 URL (value) */
		BASE_URL_INVALID,
		/** http·https가 아닌 기본 URL (value) */
		BASE_URL_SCHEME,
		/** 호스트가 없는 기본 URL (value) */
		BASE_URL_HOST,
		/** query·fragment가 있는 기본 URL (value) */
		BASE_URL_QUERY,
		/** 변수 이름이 이미 시크릿 이름 (name) */
		USED_AS_SECRET,
		/** 시크릿 이름이 이미 변수 이름 (name) */
		USED_AS_VARIABLE,
		/** 이메일 형식 오류 (email) */
		EMAIL_INVALID,
		/** 서버에 암호화 키가 없어 시크릿·Webhook을 저장할 수 없음 */
		SECRETS_DISABLED,
		/** 서버에 암호화 키가 없어 환경의 시크릿을 읽을 수 없음 (실행 시) */
		SECRETS_KEY_MISSING,
		/** 암호화 키가 바뀌어 환경의 시크릿을 읽을 수 없음 (실행 시) */
		SECRETS_UNREADABLE,
		/** 비밀번호가 최소 길이보다 짧음 (min) */
		PASSWORD_TOO_SHORT
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
