package com.sentinel.runner.assertion;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static com.sentinel.runner.assertion.AssertionTestSupport.response;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SimpleAssertionsTest {

	@ParameterizedTest
	@CsvSource({ "200, 200, true", "2xx, 204, true", "2xx, 302, false", "40x, 404, true", "40x, 410, false",
			"5XX, 503, true" })
	void matchesStatusCodePatterns(String pattern, int status, boolean passed) {
		AssertionResult result = new StatusCodeAssertion(List.of(pattern)).evaluate(response(status, ""));

		assertThat(result.passed()).isEqualTo(passed);
		assertThat(result.message()).isEqualTo("was " + status);
	}

	@Test
	void passesWhenAnyStatusPatternMatches() {
		assertThat(new StatusCodeAssertion(List.of("200", "201")).evaluate(response(201, "")).passed()).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = { "20", "2000", "600", "2y0", "" })
	void rejectsInvalidStatusPatterns(String pattern) {
		assertThatThrownBy(() -> new StatusCodeAssertion(List.of(pattern))).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsEmptyStatusList() {
		assertThatThrownBy(() -> new StatusCodeAssertion(List.of())).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new StatusCodeAssertion(null)).isInstanceOf(IllegalArgumentException.class);
	}

	@ParameterizedTest
	@CsvSource({ "1999, true", "2000, true", "2001, false" })
	void includesResponseTimeBoundary(long elapsedMillis, boolean passed) {
		AssertionResult result = new ResponseTimeAssertion(2000)
			.evaluate(response(200, Map.of(), "", Duration.ofMillis(elapsedMillis)));

		assertThat(result.passed()).isEqualTo(passed);
		assertThat(result.message()).isEqualTo("was " + elapsedMillis + "ms");
	}

	@Test
	void rejectsNonPositiveResponseTime() {
		assertThatThrownBy(() -> new ResponseTimeAssertion(0)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void evaluatesHeadersCaseInsensitively() {
		var exchange = response(200, Map.of("content-type", List.of("application/json; charset=utf-8")), "",
				Duration.ZERO);

		assertThat(new HeaderAssertion("Content-Type", Operator.EXISTS, null).evaluate(exchange).passed()).isTrue();
		assertThat(new HeaderAssertion("Content-Type", Operator.CONTAINS, "json").evaluate(exchange).passed()).isTrue();
		assertThat(new HeaderAssertion("Content-Type", Operator.EQUALS, "application/json").evaluate(exchange).passed())
			.isFalse();
		assertThat(new HeaderAssertion("Content-Type", Operator.MATCHES, "^application/.*json").evaluate(exchange)
			.passed()).isTrue();
		assertThat(new HeaderAssertion("X-Missing", Operator.NOT_EXISTS, null).evaluate(exchange).passed()).isTrue();
		assertThat(new HeaderAssertion("X-Missing", Operator.EQUALS, "a").evaluate(exchange).message())
			.isEqualTo("header absent");
	}

	@Test
	void comparesEveryValueOfARepeatedHeader() {
		var exchange = response(200, Map.of("Set-Cookie", List.of("a=1", "b=2")), "", Duration.ZERO);

		AssertionResult equals = new HeaderAssertion("Set-Cookie", Operator.EQUALS, "b=2").evaluate(exchange);
		assertThat(equals.passed()).isTrue();
		assertThat(equals.message()).isEqualTo("was [a=1, b=2]");
		assertThat(new HeaderAssertion("Set-Cookie", Operator.NOT_EQUALS, "b=2").evaluate(exchange).passed()).isFalse();
		assertThat(new HeaderAssertion("Set-Cookie", Operator.NOT_EQUALS, "c=3").evaluate(exchange).passed()).isTrue();
		assertThat(new HeaderAssertion("X-Missing", Operator.EXISTS, null).evaluate(exchange).passed()).isFalse();
	}

	@Test
	void validatesHeaderAssertion() {
		assertThatThrownBy(() -> new HeaderAssertion("X", Operator.GREATER_THAN, "1"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new HeaderAssertion("X", Operator.EQUALS, null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new HeaderAssertion("X", Operator.MATCHES, "(")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new HeaderAssertion(" ", Operator.EXISTS, null)).hasMessage("header.name must not be blank");
		assertThatThrownBy(() -> new HeaderAssertion("X", null, null)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void checksBodyContains() {
		assertThat(new BodyContainsAssertion("OK").evaluate(response(200, "status: OK")).passed()).isTrue();
		assertThat(new BodyContainsAssertion("OK").evaluate(response(200, "status: ok")).passed()).isFalse();
		assertThatThrownBy(() -> new BodyContainsAssertion("")).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void appliesDefaultStatusAssertionWhenNoneDefined() {
		assertThat(AssertionEvaluator.evaluate(List.of(), response(500, ""))).singleElement().satisfies(result -> {
			assertThat(result.description()).isEqualTo("status in [2xx]");
			assertThat(result.passed()).isFalse();
		});
		assertThat(AssertionEvaluator.evaluate(null, response(200, "")).getFirst().passed()).isTrue();
	}

	@Test
	void recordsARuleThatThrowsAsAFailureAndKeepsEvaluating() {
		BodyContainsAssertion broken = mock(BodyContainsAssertion.class);
		when(broken.evaluate(any())).thenThrow(new IllegalStateException("boom"));
		when(broken.describe()).thenReturn("body contains \"x\"");

		List<AssertionResult> results = AssertionEvaluator.evaluate(
				List.of(broken, new StatusCodeAssertion(List.of("200"))), response(200, ""));

		assertThat(results).extracting(AssertionResult::passed).containsExactly(false, true);
		assertThat(results.getFirst().message()).isEqualTo("evaluation error: boom");
	}

	@Test
	void rejectsAMissingStatusCode() {
		assertThatThrownBy(() -> new StatusCodeAssertion(Arrays.asList("200", null)))
			.hasMessageContaining("invalid status code pattern: ''");
	}

	@Test
	void evaluatesEveryAssertionEvenAfterFailure() {
		List<AssertionResult> results = AssertionEvaluator.evaluate(
				List.of(new StatusCodeAssertion(List.of("200")), new BodyContainsAssertion("x")), response(500, "x"));

		assertThat(results).extracting(AssertionResult::passed).containsExactly(false, true);
	}

}
