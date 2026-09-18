package com.sentinel.runner.assertion;

import java.util.List;

import com.sentinel.runner.HttpExchange;

/**
 * 케이스의 판정 규칙 일괄 평가.
 * <p>
 * 규칙 미지정 시 기본 판정 {@code status in [2xx]} 적용. 개별 규칙 평가 중 예외는 해당 규칙 실패로 기록하고 나머지 계속 평가.
 */
public final class AssertionEvaluator {

	public static final List<Assertion> DEFAULT = List.of(new StatusCodeAssertion(List.of("2xx")));

	private AssertionEvaluator() {
	}

	public static List<AssertionResult> evaluate(List<Assertion> assertions, HttpExchange exchange) {
		List<Assertion> effective = assertions == null || assertions.isEmpty() ? DEFAULT : assertions;
		return effective.stream().map(assertion -> evaluateSafely(assertion, exchange)).toList();
	}

	private static AssertionResult evaluateSafely(Assertion assertion, HttpExchange exchange) {
		try {
			return assertion.evaluate(exchange);
		}
		catch (RuntimeException ex) {
			return AssertionResult.fail(assertion, "evaluation error: " + ex.getMessage());
		}
	}

}
