package com.sentinel.runner.assertion;

import java.util.List;
import java.util.Map;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.InvalidJsonException;
import com.jayway.jsonpath.InvalidPathException;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.sentinel.runner.HttpExchange;
import com.sentinel.runner.InvalidValueException;
import com.sentinel.runner.InvalidValueException.Code;

/**
 * 응답 본문 JSON 경로 판정. 예: {@code $.status == "UP"}, {@code $.items.length() >= 1}.
 * <ul>
 * <li>{@code EQUALS}·{@code NOT_EQUALS}: JSON 값 비교, 숫자는 표현 무관({@code 1 == 1.0})</li>
 * <li>{@code CONTAINS}: 문자열 부분 일치 또는 배열 원소 포함</li>
 * <li>{@code MATCHES}: 문자열 정규식 부분 일치</li>
 * <li>대소 비교: 양쪽 모두 숫자일 때만 판정, 그 외 타입 불일치로 실패</li>
 * </ul>
 */
public record JsonPathAssertion(String path, Operator operator, Object expected) implements Assertion {

	public JsonPathAssertion {
		if (path == null || path.isBlank()) {
			throw new IllegalArgumentException("jsonPath.path must not be blank");
		}
		try {
			JsonPath.compile(path);
		}
		catch (InvalidPathException ex) {
			throw new InvalidValueException(Code.INVALID_JSON_PATH, Map.of("expression", path), "invalid JSON path: " + path,
					ex);
		}
		if (operator == null) {
			throw new IllegalArgumentException("jsonPath.operator is required");
		}
		if (operator.isNumericComparison() && !(expected instanceof Number)) {
			throw new IllegalArgumentException("jsonPath " + operator + " requires a numeric expected value");
		}
		if ((operator == Operator.MATCHES || operator == Operator.CONTAINS) && expected == null) {
			throw new IllegalArgumentException("jsonPath " + operator + " requires an expected value");
		}
		if (operator == Operator.MATCHES) {
			if (!(expected instanceof String regex)) {
				throw new IllegalArgumentException("jsonPath MATCHES requires a string regex");
			}
			Values.compileRegex(regex);
		}
	}

	@Override
	public AssertionResult evaluate(HttpExchange exchange) {
		Object document;
		try {
			document = Configuration.defaultConfiguration().jsonProvider().parse(exchange.body());
		}
		catch (InvalidJsonException | IllegalArgumentException ex) {
			return AssertionResult.fail(this, "response body is not JSON");
		}
		// json-smart 관대 모드는 HTML 등 비JSON 본문을 문자열 하나로 해석하므로 객체·배열만 JSON 문서로 인정
		if (!(document instanceof Map<?, ?>) && !(document instanceof List<?>)) {
			return AssertionResult.fail(this, "response body is not JSON");
		}

		Object actual;
		try {
			actual = JsonPath.read(document, path);
		}
		catch (PathNotFoundException ex) {
			return operator == Operator.NOT_EXISTS ? AssertionResult.pass(this, "path absent")
					: AssertionResult.fail(this, "path not found");
		}

		String was = "was " + Values.format(actual);
		boolean passed;
		switch (operator) {
			case EXISTS -> passed = true;
			case NOT_EXISTS -> passed = false;
			case EQUALS -> passed = Values.jsonEquals(actual, expected);
			case NOT_EQUALS -> passed = !Values.jsonEquals(actual, expected);
			case CONTAINS -> {
				if (actual instanceof String text && expected instanceof String part) {
					passed = text.contains(part);
				}
				else if (actual instanceof List<?> items) {
					passed = items.stream().anyMatch(item -> Values.jsonEquals(item, expected));
				}
				else {
					return typeMismatch("string or array", actual);
				}
			}
			case MATCHES -> {
				if (!(actual instanceof String text)) {
					return typeMismatch("string", actual);
				}
				passed = Values.compileRegex((String) expected).matcher(text).find();
			}
			default -> {
				if (!(actual instanceof Number number)) {
					return typeMismatch("number", actual);
				}
				int comparison = Values.toDecimal(number).compareTo(Values.toDecimal((Number) expected));
				passed = switch (operator) {
					case GREATER_THAN -> comparison > 0;
					case GREATER_THAN_OR_EQUAL -> comparison >= 0;
					case LESS_THAN -> comparison < 0;
					case LESS_THAN_OR_EQUAL -> comparison <= 0;
					default -> throw new IllegalStateException("unsupported operator " + operator);
				};
			}
		}
		return passed ? AssertionResult.pass(this, was) : AssertionResult.fail(this, was);
	}

	private AssertionResult typeMismatch(String expectedType, Object actual) {
		return AssertionResult.fail(this,
				"type mismatch: expected " + expectedType + " but was " + Values.typeOf(actual) + " " + Values.format(actual));
	}

	@Override
	public String describe() {
		return path + " " + operator.symbol() + (operator.needsValue() ? " " + Values.format(expected) : "");
	}

}
