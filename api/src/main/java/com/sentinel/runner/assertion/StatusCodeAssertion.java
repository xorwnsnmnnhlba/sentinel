package com.sentinel.runner.assertion;

import java.util.List;
import java.util.regex.Pattern;

import com.sentinel.runner.HttpExchange;

/**
 * 상태 코드 판정. {@code 200}처럼 정확한 값 또는 {@code 2xx}, {@code 40x}처럼 {@code x} 와일드카드 사용.
 */
public record StatusCodeAssertion(List<String> anyOf) implements Assertion {

	private static final Pattern CODE = Pattern.compile("[1-5][0-9x]{2}");

	public StatusCodeAssertion {
		if (anyOf == null || anyOf.isEmpty()) {
			throw new IllegalArgumentException("statusCode.anyOf must not be empty");
		}
		anyOf = anyOf.stream().map(code -> code == null ? "" : code.strip().toLowerCase()).toList();
		for (String code : anyOf) {
			if (!CODE.matcher(code).matches()) {
				throw new IllegalArgumentException("invalid status code pattern: '" + code + "' (e.g. 200, 2xx)");
			}
		}
	}

	@Override
	public AssertionResult evaluate(HttpExchange exchange) {
		String actual = String.valueOf(exchange.statusCode());
		boolean passed = anyOf.stream().anyMatch(pattern -> matches(pattern, actual));
		return passed ? AssertionResult.pass(this, "was " + actual) : AssertionResult.fail(this, "was " + actual);
	}

	private static boolean matches(String pattern, String actual) {
		if (actual.length() != 3) {
			return false;
		}
		for (int i = 0; i < 3; i++) {
			if (pattern.charAt(i) != 'x' && pattern.charAt(i) != actual.charAt(i)) {
				return false;
			}
		}
		return true;
	}

	@Override
	public String describe() {
		return "status in " + anyOf;
	}

}
