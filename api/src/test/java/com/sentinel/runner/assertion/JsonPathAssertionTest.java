package com.sentinel.runner.assertion;

import java.util.List;
import java.util.Map;

import com.sentinel.runner.InvalidValueException;
import com.sentinel.runner.InvalidValueException.Code;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static com.sentinel.runner.assertion.AssertionTestSupport.response;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonPathAssertionTest {

	private static final String BODY = """
			{"status": "UP", "amount": 1000, "rate": 1.5, "memo": null, "active": true,
			 "items": [{"id": 1}, {"id": 2}], "tags": ["a", "b"], "code": "1000"}
			""";

	@Test
	void comparesStringsBooleansAndNull() {
		assertThat(evaluate("$.status", Operator.EQUALS, "UP").passed()).isTrue();
		assertThat(evaluate("$.status", Operator.NOT_EQUALS, "DOWN").passed()).isTrue();
		assertThat(evaluate("$.status", Operator.NOT_EQUALS, "UP").passed()).isFalse();
		assertThat(evaluate("$.active", Operator.EQUALS, true).passed()).isTrue();
		assertThat(evaluate("$.memo", Operator.EQUALS, null).passed()).isTrue();
		assertThat(evaluate("$.memo", Operator.EXISTS, null).passed()).isTrue();
	}

	@Test
	void comparesNumbersByValueRegardlessOfRepresentation() {
		assertThat(evaluate("$.amount", Operator.EQUALS, 1000.0).passed()).isTrue();
		assertThat(evaluate("$.amount", Operator.EQUALS, 1000L).passed()).isTrue();
		assertThat(evaluate("$.rate", Operator.EQUALS, 1.50).passed()).isTrue();
	}

	@Test
	void doesNotTreatNumericStringAsNumber() {
		AssertionResult result = evaluate("$.code", Operator.EQUALS, 1000);

		assertThat(result.passed()).isFalse();
		assertThat(result.message()).isEqualTo("was \"1000\"");
	}

	@ParameterizedTest
	@CsvSource({ "GREATER_THAN, 999, true", "GREATER_THAN, 1000, false", "GREATER_THAN_OR_EQUAL, 1000, true",
			"LESS_THAN, 1000, false", "LESS_THAN_OR_EQUAL, 1000, true", "LESS_THAN, 1000.5, true",
			// 이상·이하도 기준을 넘으면 실패 (경계에서 통과만 확인하면 항상 통과로 바뀌어도 모름)
			"GREATER_THAN_OR_EQUAL, 1000.5, false", "LESS_THAN_OR_EQUAL, 999.5, false" })
	void comparesNumericBoundaries(Operator operator, double expected, boolean passed) {
		assertThat(evaluate("$.amount", operator, expected).passed()).isEqualTo(passed);
	}

	@Test
	void failsNumericComparisonOnTypeMismatch() {
		AssertionResult string = evaluate("$.status", Operator.GREATER_THAN, 1);
		AssertionResult nullValue = evaluate("$.memo", Operator.LESS_THAN, 1);

		assertThat(string.passed()).isFalse();
		assertThat(string.message()).isEqualTo("type mismatch: expected number but was string \"UP\"");
		assertThat(nullValue.message()).isEqualTo("type mismatch: expected number but was null null");
		assertThat(evaluate("$.active", Operator.GREATER_THAN, 1).message())
			.isEqualTo("type mismatch: expected number but was boolean true");
		assertThat(evaluate("$.tags", Operator.GREATER_THAN, 1).message())
			.startsWith("type mismatch: expected number but was array ");
		assertThat(evaluate("$.items[0]", Operator.GREATER_THAN, 1).message())
			.startsWith("type mismatch: expected number but was object ");
	}

	@Test
	void containsWorksOnStringsAndArrays() {
		assertThat(evaluate("$.status", Operator.CONTAINS, "U").passed()).isTrue();
		assertThat(evaluate("$.tags", Operator.CONTAINS, "b").passed()).isTrue();
		assertThat(evaluate("$.items[*].id", Operator.CONTAINS, 2).passed()).isTrue();
		assertThat(evaluate("$.items[*].id", Operator.CONTAINS, 3).passed()).isFalse();
		assertThat(evaluate("$.amount", Operator.CONTAINS, "1").message()).startsWith("type mismatch");
	}

	@Test
	void comparesArraysAndObjectsStructurally() {
		assertThat(evaluate("$.tags", Operator.EQUALS, List.of("a", "b")).passed()).isTrue();
		assertThat(evaluate("$.items[0]", Operator.EQUALS, Map.of("id", 1)).passed()).isTrue();
		assertThat(evaluate("$.tags.length()", Operator.EQUALS, 2).passed()).isTrue();
	}

	@Test
	void failsWhenArraysOrObjectsDifferInSizeKeysOrValues() {
		assertThat(evaluate("$.tags", Operator.EQUALS, List.of("a")).passed()).isFalse();
		assertThat(evaluate("$.tags", Operator.EQUALS, List.of("a", "c")).passed()).isFalse();
		assertThat(evaluate("$.items[0]", Operator.EQUALS, Map.of("id", 2)).passed()).isFalse();
		assertThat(evaluate("$.items[0]", Operator.EQUALS, Map.of("key", 1)).passed()).isFalse();
		assertThat(evaluate("$.items[0]", Operator.EQUALS, Map.of("id", 1, "name", "x")).passed()).isFalse();
	}

	@Test
	void shortensLongValuesInMessages() {
		String longText = "x".repeat(250);
		AssertionResult result = new JsonPathAssertion("$.text", Operator.EQUALS, "y")
			.evaluate(response(200, "{\"text\": \"" + longText + "\"}"));

		// 따옴표 포함 200자까지와 "..." (알림·결과 화면이 길어지지 않도록)
		assertThat(result.message()).isEqualTo("was \"" + "x".repeat(199) + "...");
	}

	@Test
	void matchesRegexOnStringsOnly() {
		assertThat(evaluate("$.status", Operator.MATCHES, "^U.$").passed()).isTrue();
		assertThat(evaluate("$.amount", Operator.MATCHES, "\\d+").message()).startsWith("type mismatch");
	}

	@Test
	void handlesMissingPathAndNonJsonBody() {
		assertThat(evaluate("$.missing", Operator.EXISTS, null).message()).isEqualTo("path not found");
		assertThat(evaluate("$.missing", Operator.NOT_EXISTS, null).passed()).isTrue();
		assertThat(evaluate("$.status", Operator.NOT_EXISTS, null).passed()).isFalse();

		AssertionResult html = new JsonPathAssertion("$.status", Operator.EXISTS, null)
			.evaluate(response(502, "<html>Bad Gateway</html>"));
		assertThat(html.passed()).isFalse();
		assertThat(html.message()).isEqualTo("response body is not JSON");
		assertThat(new JsonPathAssertion("$.a", Operator.EXISTS, null).evaluate(response(204, "")).passed()).isFalse();
		// 중간에 끊긴 JSON (응답 크기 상한에서 잘린 본문 등)
		assertThat(new JsonPathAssertion("$.a", Operator.EXISTS, null).evaluate(response(200, "{\"a\": [1, 2")).message())
			.isEqualTo("response body is not JSON");
	}

	@Test
	void describesRule() {
		assertThat(new JsonPathAssertion("$.status", Operator.EQUALS, "UP").describe()).isEqualTo("$.status == \"UP\"");
		assertThat(new JsonPathAssertion("$.id", Operator.EXISTS, null).describe()).isEqualTo("$.id exists");
	}

	@Test
	void validatesRule() {
		// 화면이 한국어 문장을 만들 종류·값 (web/lib/input-error.ts)
		assertThatThrownBy(() -> new JsonPathAssertion("$.[", Operator.EXISTS, null))
			.isInstanceOfSatisfying(InvalidValueException.class, ex -> {
				assertThat(ex.code()).isEqualTo(Code.INVALID_JSON_PATH);
				assertThat(ex.params()).isEqualTo(Map.of("expression", "$.["));
			});
		assertThatThrownBy(() -> new JsonPathAssertion("$.a", Operator.GREATER_THAN, "1"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new JsonPathAssertion("$.a", Operator.MATCHES, "("))
			.isInstanceOfSatisfying(InvalidValueException.class, ex -> {
				assertThat(ex.code()).isEqualTo(Code.INVALID_REGEX);
				assertThat(ex.params()).isEqualTo(Map.of("expression", "("));
			});
		assertThatThrownBy(() -> new JsonPathAssertion("$.a", Operator.CONTAINS, null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new JsonPathAssertion(" ", Operator.EXISTS, null))
			.hasMessage("jsonPath.path must not be blank");
		assertThatThrownBy(() -> new JsonPathAssertion("$.a", null, null)).hasMessage("jsonPath.operator is required");
		assertThatThrownBy(() -> new JsonPathAssertion("$.a", Operator.MATCHES, 1))
			.hasMessage("jsonPath MATCHES requires a string regex");
	}

	private static AssertionResult evaluate(String path, Operator operator, Object expected) {
		return new JsonPathAssertion(path, operator, expected).evaluate(response(200, BODY));
	}

}
