package com.sentinel.runner.assertion;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.sentinel.runner.HttpExchange;
import com.sentinel.runner.HttpMethod;
import com.sentinel.runner.ResolvedRequest;

final class AssertionTestSupport {

	private AssertionTestSupport() {
	}

	static HttpExchange response(int status, String body) {
		return response(status, Map.of(), body, Duration.ofMillis(100));
	}

	static HttpExchange response(int status, Map<String, List<String>> headers, String body, Duration elapsed) {
		return new HttpExchange(ResolvedRequest.of(HttpMethod.GET, "https://gw/a"), status, headers, body, elapsed);
	}

}
