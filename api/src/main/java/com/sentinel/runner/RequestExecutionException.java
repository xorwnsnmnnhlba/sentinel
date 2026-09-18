package com.sentinel.runner;

import java.time.Duration;

/**
 * 응답을 받지 못한 전송 실패.
 */
public class RequestExecutionException extends RuntimeException {

	public enum Reason {
		TIMEOUT, CONNECTION_FAILED, IO_ERROR
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
