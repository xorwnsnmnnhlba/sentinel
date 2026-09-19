package com.sentinel.runner.assertion;

/**
 * @param description 규칙 설명
 * @param message 실제 값 또는 실패 사유
 */
public record AssertionResult(String description, boolean passed, String message) {

	public static AssertionResult pass(Assertion assertion, String message) {
		return new AssertionResult(assertion.describe(), true, message);
	}

	public static AssertionResult fail(Assertion assertion, String message) {
		return new AssertionResult(assertion.describe(), false, message);
	}

}
