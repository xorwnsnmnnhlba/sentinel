package com.sentinel.runner.assertion;

import com.sentinel.runner.HttpExchange;

/**
 * 응답 시간 상한(경계값 포함).
 */
public record ResponseTimeAssertion(long maxMillis) implements Assertion {

	public ResponseTimeAssertion {
		if (maxMillis <= 0) {
			throw new IllegalArgumentException("responseTime.maxMillis must be positive");
		}
	}

	@Override
	public AssertionResult evaluate(HttpExchange exchange) {
		long actual = exchange.elapsed().toMillis();
		String message = "was " + actual + "ms";
		return actual <= maxMillis ? AssertionResult.pass(this, message) : AssertionResult.fail(this, message);
	}

	@Override
	public String describe() {
		return "response time <= " + maxMillis + "ms";
	}

}
