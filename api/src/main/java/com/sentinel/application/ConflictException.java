package com.sentinel.application;

import java.util.Map;

/**
 * 사용 중인 항목과의 충돌. 예: 같은 부모 아래 이름 중복.
 * 화면이 영어 메시지 대신 종류로 구분해야 하는 충돌은 code·params를 붙임 (409 응답에 함께)
 */
public class ConflictException extends RuntimeException {

	private final Code code;

	private final Map<String, String> params;

	/** 충돌 종류. 괄호 안은 params 이름 */
	public enum Code {
		/** 같은 부모 아래 같은 이름 (name) */
		NAME_IN_USE,
		/** 아직 진행 중인 실행이라 다시 실행할 수 없음 */
		RUN_STILL_RUNNING,
		/** 다시 실행할 실패 케이스가 없음 */
		NOTHING_TO_RERUN
	}

	public ConflictException(String message) {
		this(null, Map.of(), message);
	}

	public ConflictException(Code code, Map<String, String> params, String message) {
		super(message);
		this.code = code;
		this.params = Map.copyOf(params);
	}

	/** 화면용 충돌 종류. 없으면 null */
	public Code code() {
		return code;
	}

	public Map<String, String> params() {
		return params;
	}

}
