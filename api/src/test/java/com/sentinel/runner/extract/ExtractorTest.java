package com.sentinel.runner.extract;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.sentinel.runner.HttpExchange;
import com.sentinel.runner.HttpMethod;
import com.sentinel.runner.ResolvedRequest;
import com.sentinel.runner.extract.Extractor.Extraction;
import com.sentinel.runner.extract.Extractor.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExtractorTest {

	private static final String BODY = """
			{"data": {"token": "abc.def", "count": 3, "ratio": 1.5, "active": true, "none": null,
			          "items": [{"id": 7, "sku": "a"}, {"id": 8, "sku": "b"}], "owner": {"id": 1}}}
			""";

	private static HttpExchange exchange(Map<String, List<String>> headers, String body) {
		ResolvedRequest request = new ResolvedRequest(HttpMethod.POST, URI.create("https://gw.dev.internal/login"),
				Map.of(), null);
		return new HttpExchange(request, 200, headers, body, Duration.ofMillis(5));
	}

	private static Extraction body(String path) {
		return new Extractor("value", Source.BODY, path, false).extract(exchange(Map.of(), BODY));
	}

	@ParameterizedTest
	@CsvSource({ "$.data.token, abc.def", "$.data.count, 3", "$.data.ratio, 1.5", "$.data.active, true",
			// 필터 경로의 결과가 1건이면 그 값
			"$.data.items[?(@.sku == 'b')].id, 8" })
	void extractsScalarValuesFromTheBody(String path, String expected) {
		Extraction extraction = body(path);

		assertThat(extraction.succeeded()).isTrue();
		assertThat(extraction.value()).isEqualTo(expected);
		assertThat(extraction.message()).isEqualTo("was \"" + expected + "\"");
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = { "$.data.missing | path not found", "$.data.none | value is null",
			"$.data.owner | value is an object; extract a string, number or boolean",
			"$.data.items[*].id | value is an array of 2 element(s); extract a string, number or boolean" })
	void failsForMissingNullOrNonScalarValues(String path, String failure) {
		Extraction extraction = body(path);

		assertThat(extraction.succeeded()).isFalse();
		assertThat(extraction.value()).isNull();
		assertThat(extraction.message()).isEqualTo(failure);
	}

	@ParameterizedTest
	@ValueSource(strings = { "<html>login</html>", "", "\"just a string\"" })
	void failsWhenTheBodyIsNotJson(String body) {
		Extraction extraction = new Extractor("token", Source.BODY, "$.token", false).extract(exchange(Map.of(), body));

		assertThat(extraction.message()).isEqualTo("response body is not JSON");
	}

	@Test
	void extractsTheFirstHeaderValueIgnoringCase() {
		Extractor location = new Extractor("orderUrl", Source.HEADER, "location", false);

		assertThat(location.extract(exchange(Map.of("Location", List.of("/orders/9", "/orders/10")), "")).value())
			.isEqualTo("/orders/9");
		assertThat(location.extract(exchange(Map.of(), "")).message()).isEqualTo("header absent");
	}

	@Test
	void hidesSecretValuesInTheMessage() {
		Extraction extraction = new Extractor("token", Source.BODY, "$.data.token", true)
			.extract(exchange(Map.of(), BODY));

		assertThat(extraction.value()).isEqualTo("abc.def");
		assertThat(extraction.message()).isEqualTo("extracted (hidden)").doesNotContain("abc.def");
	}

	@Test
	void shortensLongValuesInTheMessage() {
		String longValue = "x".repeat(300);
		Extraction extraction = new Extractor("value", Source.BODY, "$.value", false)
			.extract(exchange(Map.of(), "{\"value\": \"" + longValue + "\"}"));

		assertThat(extraction.value()).isEqualTo(longValue);
		assertThat(extraction.message()).hasSize(4 + 200 + 3).endsWith("...");
	}

	@Test
	void describesWhereTheValueComesFrom() {
		assertThat(new Extractor("token", Source.BODY, "$.data.token", true).describe())
			.isEqualTo("extract {{token}} from $.data.token");
		assertThat(new Extractor("orderUrl", Source.HEADER, "Location", false).describe())
			.isEqualTo("extract {{orderUrl}} from header Location");
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "user id", "{{token}}", "토큰" })
	void rejectsNamesThatCannotBeUsedAsVariables(String name) {
		assertThatThrownBy(() -> new Extractor(name, Source.BODY, "$.a", false))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("extractor.name");
	}

	@Test
	void rejectsMissingSourceOrExpressionAndInvalidJsonPaths() {
		assertThatThrownBy(() -> new Extractor("a", null, "$.a", false)).hasMessageContaining("extractor.from");
		assertThatThrownBy(() -> new Extractor("a", Source.HEADER, " ", false))
			.hasMessageContaining("extractor.expression");
		assertThatThrownBy(() -> new Extractor("a", Source.BODY, "$.[", false))
			.hasMessageContaining("invalid JSON path");
		// 헤더 이름은 JSON 경로로 검사하지 않음
		assertThat(new Extractor("a", Source.HEADER, "X-Request-Id", false).expression()).isEqualTo("X-Request-Id");
		assertThat(new Extractor("a.b-c_1", Source.BODY, "$.a", false).name()).isEqualTo("a.b-c_1");
	}

	@Test
	void readsStoredJsonWithoutTheSecretFlag() {
		Extractor extractor = JsonMapper.builder().build().readValue("""
				{"name": "orderId", "from": "BODY", "expression": "$.id"}""", Extractor.class);

		assertThat(extractor).isEqualTo(new Extractor("orderId", Source.BODY, "$.id", false));
	}

}
