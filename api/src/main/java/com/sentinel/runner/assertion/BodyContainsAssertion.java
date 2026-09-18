package com.sentinel.runner.assertion;

import com.sentinel.runner.HttpExchange;

public record BodyContainsAssertion(String text) implements Assertion {

	public BodyContainsAssertion {
		if (text == null || text.isEmpty()) {
			throw new IllegalArgumentException("bodyContains.text must not be empty");
		}
	}

	@Override
	public AssertionResult evaluate(HttpExchange exchange) {
		return exchange.body().contains(text) ? AssertionResult.pass(this, "found")
				: AssertionResult.fail(this, "not found in response body");
	}

	@Override
	public String describe() {
		return "body contains " + Values.format(text);
	}

}
