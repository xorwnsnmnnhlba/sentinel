package com.sentinel.application;

/**
 * 수정 화면을 연 뒤 다른 곳에서 먼저 고친 항목을 저장하려 함. 요청의 {@code version}이 지금 값과 다름.
 * 그대로 저장하면 그사이의 변경(예: 다른 사람이 추가한 환경 변수)을 덮어씀
 */
public class StaleVersionException extends RuntimeException {

	public StaleVersionException(String resource, long id, long expected, long actual) {
		super(resource + " " + id + " is at version " + actual + ", not " + expected);
	}

	/** 기대한 version이 있으면 지금 값과 같은지 확인. null이면 확인하지 않음 (이전 API 호출 호환) */
	static void check(String resource, long id, Long expected, long actual) {
		if (expected != null && expected != actual) {
			throw new StaleVersionException(resource, id, expected, actual);
		}
	}

}
