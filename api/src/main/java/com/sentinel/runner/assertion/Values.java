package com.sentinel.runner.assertion;

import java.math.BigDecimal;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 판정 값 비교·표시 유틸리티. JSON 값(문자열, 숫자, 불리언, null, 배열, 객체) 기준.
 */
final class Values {

	private static final int MAX_FORMAT_LENGTH = 200;

	private Values() {
	}

	static Pattern compileRegex(String regex) {
		try {
			return Pattern.compile(regex);
		}
		catch (PatternSyntaxException ex) {
			throw new IllegalArgumentException("invalid regex: " + regex, ex);
		}
	}

	/** 숫자는 표현과 무관하게 값으로 비교({@code 1 == 1.0}), 배열·객체는 재귀 비교 */
	static boolean jsonEquals(Object a, Object b) {
		if (a instanceof Number x && b instanceof Number y) {
			return toDecimal(x).compareTo(toDecimal(y)) == 0;
		}
		if (a instanceof List<?> x && b instanceof List<?> y) {
			if (x.size() != y.size()) {
				return false;
			}
			Iterator<?> left = x.iterator();
			Iterator<?> right = y.iterator();
			while (left.hasNext()) {
				if (!jsonEquals(left.next(), right.next())) {
					return false;
				}
			}
			return true;
		}
		if (a instanceof Map<?, ?> x && b instanceof Map<?, ?> y) {
			return x.size() == y.size() && x.entrySet()
				.stream()
				.allMatch(entry -> y.containsKey(entry.getKey()) && jsonEquals(entry.getValue(), y.get(entry.getKey())));
		}
		return Objects.equals(a, b);
	}

	static BigDecimal toDecimal(Number number) {
		return number instanceof BigDecimal decimal ? decimal : new BigDecimal(number.toString());
	}

	static String typeOf(Object value) {
		return switch (value) {
			case null -> "null";
			case String s -> "string";
			case Number n -> "number";
			case Boolean b -> "boolean";
			case List<?> l -> "array";
			case Map<?, ?> m -> "object";
			default -> value.getClass().getSimpleName();
		};
	}

	static String format(Object value) {
		String text = switch (value) {
			case null -> "null";
			case String s -> "\"" + s + "\"";
			default -> String.valueOf(value);
		};
		return text.length() <= MAX_FORMAT_LENGTH ? text : text.substring(0, MAX_FORMAT_LENGTH) + "...";
	}

}
