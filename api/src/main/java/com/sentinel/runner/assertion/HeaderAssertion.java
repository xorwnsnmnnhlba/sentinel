package com.sentinel.runner.assertion;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import com.sentinel.runner.HttpExchange;

/**
 * 응답 헤더 판정. 헤더명 대소문자 무관, 같은 이름의 값이 여러 개면 하나라도 만족 시 통과({@code NOT_EQUALS}는 전부 불일치 시 통과).
 */
public record HeaderAssertion(String name, Operator operator, String value) implements Assertion {

	private static final Set<Operator> SUPPORTED = EnumSet.of(Operator.EXISTS, Operator.NOT_EXISTS, Operator.EQUALS,
			Operator.NOT_EQUALS, Operator.CONTAINS, Operator.MATCHES);

	public HeaderAssertion {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("header.name must not be blank");
		}
		if (operator == null || !SUPPORTED.contains(operator)) {
			throw new IllegalArgumentException("header.operator must be one of " + SUPPORTED);
		}
		if (operator.needsValue() && value == null) {
			throw new IllegalArgumentException("header.value is required for " + operator);
		}
		if (operator == Operator.MATCHES) {
			Values.compileRegex(value);
		}
	}

	@Override
	public AssertionResult evaluate(HttpExchange exchange) {
		List<String> actual = exchange.headers().getOrDefault(name, List.of());
		String message = actual.isEmpty() ? "header absent" : "was " + (actual.size() == 1 ? Values.format(actual.getFirst()) : actual);
		boolean passed = switch (operator) {
			case EXISTS -> !actual.isEmpty();
			case NOT_EXISTS -> actual.isEmpty();
			case EQUALS -> actual.contains(value);
			case NOT_EQUALS -> !actual.contains(value);
			case CONTAINS -> actual.stream().anyMatch(v -> v.contains(value));
			case MATCHES -> {
				Pattern pattern = Values.compileRegex(value);
				yield actual.stream().anyMatch(v -> pattern.matcher(v).find());
			}
			default -> throw new IllegalStateException("unsupported operator " + operator);
		};
		return passed ? AssertionResult.pass(this, message) : AssertionResult.fail(this, message);
	}

	@Override
	public String describe() {
		return "header " + name + " " + operator.symbol() + (operator.needsValue() ? " " + Values.format(value) : "");
	}

}
