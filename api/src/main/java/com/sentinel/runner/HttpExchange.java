package com.sentinel.runner;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 요청 1건과 그에 대한 응답. 상태 코드와 무관하게 응답을 받은 경우의 결과.
 */
public record HttpExchange(
		ResolvedRequest request,
		int statusCode,
		Map<String, List<String>> headers,
		String body,
		Duration elapsed) {

	public HttpExchange {
		Objects.requireNonNull(request, "request");
		Objects.requireNonNull(elapsed, "elapsed");
		headers = Headers.copyOf(headers);
		body = body == null ? "" : body;
	}

}
