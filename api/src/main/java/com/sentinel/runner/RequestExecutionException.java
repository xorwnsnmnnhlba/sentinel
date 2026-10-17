package com.sentinel.runner;

import java.time.Duration;

/**
 * 응답을 받지 못한 전송 실패.
 */
public class RequestExecutionException extends RuntimeException {

	public enum Reason {
		TIMEOUT, CONNECTION_FAILED, IO_ERROR,
		/** 인증서·TLS 연결 수립 실패 (만료·신뢰할 수 없는 발급자·이름 불일치·지원 버전 없음). 다시 보내도 같음 */
		TLS_ERROR,
		/** 전송하지 않은 목적지. 링크 로컬·클라우드 메타데이터 주소 */
		BLOCKED_DESTINATION
	}

	private final Reason reason;

	private final ResolvedRequest request;

	private final Duration elapsed;

	public RequestExecutionException(Reason reason, ResolvedRequest request, Duration elapsed, Throwable cause) {
		super(reason + ": " + request.method() + " " + request.uri(), cause);
		this.reason = reason;
		this.request = request;
		this.elapsed = elapsed;
	}

	/**
	 * 전송 전에 거부한 경우. 원인 예외 대신 사유를 메시지로 기록
	 */
	public RequestExecutionException(Reason reason, ResolvedRequest request, String detail) {
		super(reason + ": " + detail);
		this.reason = reason;
		this.request = request;
		this.elapsed = Duration.ZERO;
	}

	public Reason reason() {
		return reason;
	}

	public ResolvedRequest request() {
		return request;
	}

	public Duration elapsed() {
		return elapsed;
	}

}
