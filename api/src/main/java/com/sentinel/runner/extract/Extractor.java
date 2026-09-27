package com.sentinel.runner.extract;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.InvalidJsonException;
import com.jayway.jsonpath.InvalidPathException;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.sentinel.runner.HttpExchange;

/**
 * 응답 값 추출 규칙. 추출한 값은 같은 스위트 실행에서 뒤에 실행하는 케이스가 {@code {{name}}}으로 사용
 * <ul>
 * <li>{@code BODY}: 응답 본문 JSON 경로. 문자열·숫자·불리언만 추출. 결과가 원소 1개인 배열(필터 경로)이면 그 원소</li>
 * <li>{@code HEADER}: 응답 헤더 값 (이름 대소문자 무관, 여러 개면 첫 값)</li>
 * </ul>
 *
 * @param name 변수 이름. {@code {{변수}}}와 같은 규칙 (영문·숫자·{@code _ . -}, 100자 이하)
 * @param from 추출 위치
 * @param expression {@code BODY}는 JSON 경로, {@code HEADER}는 헤더 이름
 * @param secret 토큰 등 가릴 값. 실행 이력(요청·응답·판정 메시지)에서 값을 가림. 생략(null)은 false
 */
public record Extractor(String name, Source from, String expression, Boolean secret) {

	public enum Source {

		BODY, HEADER

	}

	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.-]{1,100}");

	private static final int MAX_EXPRESSION_LENGTH = 500;

	private static final int MAX_FORMAT_LENGTH = 200;

	public Extractor {
		secret = Boolean.TRUE.equals(secret);
		if (name == null || !NAME.matcher(name).matches()) {
			throw new IllegalArgumentException(
					"extractor.name must be 1-100 letters, digits, '_', '.' or '-': " + name);
		}
		if (from == null) {
			throw new IllegalArgumentException("extractor.from is required");
		}
		if (expression == null || expression.isBlank() || expression.length() > MAX_EXPRESSION_LENGTH) {
			throw new IllegalArgumentException("extractor.expression must be 1-" + MAX_EXPRESSION_LENGTH + " characters");
		}
		if (from == Source.BODY) {
			try {
				JsonPath.compile(expression);
			}
			catch (InvalidPathException ex) {
				throw new IllegalArgumentException("invalid JSON path: " + expression, ex);
			}
		}
	}

	/** 편의 생성자. 가리지 않는 값 */
	public Extractor(String name, Source from, String expression) {
		this(name, from, expression, false);
	}

	public Extraction extract(HttpExchange exchange) {
		try {
			return from == Source.BODY ? fromBody(exchange.body()) : fromHeader(exchange.headers());
		}
		catch (RuntimeException ex) {
			return Extraction.failed(this, "evaluation error: " + ex.getMessage());
		}
	}

	private Extraction fromBody(String body) {
		Object document;
		try {
			document = Configuration.defaultConfiguration().jsonProvider().parse(body);
		}
		catch (InvalidJsonException | IllegalArgumentException ex) {
			return Extraction.failed(this, "response body is not JSON");
		}
		// json-smart 관대 모드는 비JSON 본문을 문자열 하나로 해석하므로 객체·배열만 JSON 문서로 인정 (JsonPathAssertion과 같음)
		if (!(document instanceof Map<?, ?>) && !(document instanceof List<?>)) {
			return Extraction.failed(this, "response body is not JSON");
		}
		Object value;
		try {
			value = JsonPath.read(document, expression);
		}
		catch (PathNotFoundException ex) {
			return Extraction.failed(this, "path not found");
		}
		if (value instanceof List<?> matches && matches.size() == 1) {
			value = matches.getFirst();
		}
		return switch (value) {
			case null -> Extraction.failed(this, "value is null");
			case String text -> Extraction.succeeded(this, text);
			case Number number -> Extraction.succeeded(this, number.toString());
			case Boolean bool -> Extraction.succeeded(this, bool.toString());
			case List<?> list -> Extraction.failed(this,
					"value is an array of " + list.size() + " element(s); extract a string, number or boolean");
			default -> Extraction.failed(this, "value is an object; extract a string, number or boolean");
		};
	}

	private Extraction fromHeader(Map<String, List<String>> headers) {
		List<String> values = headers.getOrDefault(expression, List.of());
		return values.isEmpty() ? Extraction.failed(this, "header absent") : Extraction.succeeded(this, values.getFirst());
	}

	/** 결과 화면용 설명. 예: {@code extract {{token}} from $.data.token} */
	public String describe() {
		return "extract {{" + name + "}} from " + (from == Source.HEADER ? "header " : "") + expression;
	}

	/**
	 * 추출 결과 1건
	 * @param value 추출한 값. 실패 시 null
	 * @param failure 실패 사유. 성공 시 null
	 */
	public record Extraction(Extractor extractor, String value, String failure) {

		static Extraction succeeded(Extractor extractor, String value) {
			return new Extraction(extractor, value, null);
		}

		static Extraction failed(Extractor extractor, String failure) {
			return new Extraction(extractor, null, failure);
		}

		public boolean succeeded() {
			return failure == null;
		}

		/** 결과 화면 메시지. 가릴 값은 값 대신 가렸다는 사실만 */
		public String message() {
			if (!succeeded()) {
				return failure;
			}
			if (extractor.secret()) {
				return "extracted (hidden)";
			}
			String quoted = "\"" + value + "\"";
			return "was " + (quoted.length() <= MAX_FORMAT_LENGTH ? quoted : quoted.substring(0, MAX_FORMAT_LENGTH) + "...");
		}

	}

}
