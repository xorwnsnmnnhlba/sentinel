package com.sentinel.runner.assertion;

import java.util.List;
import java.util.Map;

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
			"LESS_THAN, 1000, false", "LESS_THAN_OR_EQUAL, 1000, true", "LESS_THAN, 1000.5, true" })
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
	}

	@Test
	void describesRule() {
		assertThat(new JsonPathAssertion("$.status", Operator.EQUALS, "UP").describe()).isEqualTo("$.status == \"UP\"");
		assertThat(new JsonPathAssertion("$.id", Operator.EXISTS, null).describe()).isEqualTo("$.id exists");
	}

	@Test
	void validatesRule() {
		assertThatThrownBy(() -> new JsonPathAssertion("$.[", Operator.EXISTS, null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new JsonPathAssertion("$.a", Operator.GREATER_THAN, "1"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new JsonPathAssertion("$.a", Operator.MATCHES, "("))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new JsonPathAssertion("$.a", Operator.CONTAINS, null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	private static AssertionResult evaluate(String path, Operator operator, Object expected) {
		return new JsonPathAssertion(path, operator, expected).evaluate(response(200, BODY));
	}

}
