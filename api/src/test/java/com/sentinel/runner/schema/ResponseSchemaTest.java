package com.sentinel.runner.schema;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ResponseSchemaTest {

	private static final String JSON = "application/json";

	private static Map<String, List<String>> fields(String body) {
		return ResponseSchema.of(JSON, body).orElseThrow().fields();
	}

	@Test
	void recordsPathsAndTypesWithoutValues() {
		ResponseSchema schema = ResponseSchema.of(JSON, """
				{"id": 7, "name": "kim", "active": true, "deletedAt": null, "profile": {"age": 30}}
				""").orElseThrow();

		assertThat(schema.fields()).containsExactly(Map.entry("$", List.of("object")),
				Map.entry("$.active", List.of("boolean")), Map.entry("$.deletedAt", List.of("null")),
				Map.entry("$.id", List.of("number")), Map.entry("$.name", List.of("string")),
				Map.entry("$.profile", List.of("object")), Map.entry("$.profile.age", List.of("number")));
		assertThat(schema.complete()).isTrue();
		// 값은 남기지 않음
		assertThat(schema.toString()).doesNotContain("kim", "30");
	}

	@Test
	void mergesAllArrayElementsIntoOnePath() {
		assertThat(fields("""
				{"items": [{"id": 1, "tag": "a"}, {"id": 2, "tag": null, "extra": [1, "x"]}], "empty": []}
				""")).containsExactly(Map.entry("$", List.of("object")), Map.entry("$.empty", List.of("array")),
				Map.entry("$.items", List.of("array")), Map.entry("$.items[]", List.of("object")),
				Map.entry("$.items[].extra", List.of("array")),
				Map.entry("$.items[].extra[]", List.of("number", "string")),
				Map.entry("$.items[].id", List.of("number")), Map.entry("$.items[].tag", List.of("null", "string")));
	}

	@Test
	void handlesTopLevelArraysAndNestedArrays() {
		assertThat(fields("[[1, 2], [true]]")).containsExactly(Map.entry("$", List.of("array")),
				Map.entry("$[]", List.of("array")), Map.entry("$[][]", List.of("boolean", "number")));
	}

	@ParameterizedTest
	@ValueSource(strings = { "{\"1001\": {\"n\": 1}, \"1002\": {\"n\": 2}}",
			"{\"2026-09-01\": {\"n\": 1}, \"2026-09-02\": {\"n\": 2}}",
			"{\"3f2b8c1e-8d4a-4b6f-9c2e-1a2b3c4d5e6f\": {\"n\": 1}, \"0e1f2a3b-4c5d-4e6f-8a9b-0c1d2e3f4a5b\": {\"n\": 2}}" })
	void collapsesIdAndDateKeysIntoAWildcard(String body) {
		assertThat(fields(body)).containsExactly(Map.entry("$", List.of("object")), Map.entry("$.*", List.of("object")),
				Map.entry("$.*.n", List.of("number")));
	}

	@Test
	void collapsesObjectsWithTooManyKeys() {
		String many = IntStream.range(0, ResponseSchema.MAX_SIBLING_KEYS + 1)
			.mapToObj(i -> "\"key" + i + "\": " + i)
			.collect(Collectors.joining(", ", "{", "}"));
		String few = IntStream.range(0, ResponseSchema.MAX_SIBLING_KEYS)
			.mapToObj(i -> "\"key" + i + "\": " + i)
			.collect(Collectors.joining(", ", "{", "}"));

		assertThat(fields(many)).containsOnlyKeys("$", "$.*");
		// 한도 이하의 일반 키는 그대로
		assertThat(fields(few)).hasSize(ResponseSchema.MAX_SIBLING_KEYS + 1).containsKey("$.key0");
	}

	@Test
	void keepsASingleNumericKeyAsIs() {
		// 키 하나만으로는 동적 키인지 알 수 없음
		assertThat(fields("{\"200\": \"OK\"}")).containsOnlyKeys("$", "$['200']");
	}

	@Test
	void quotesKeysThatAreNotIdentifiers() {
		assertThat(fields("{\"a.b\": 1, \"it's\": 2, \"kebab-case\": 3}")).containsOnlyKeys("$", "$['a.b']",
				"$['it\\'s']", "$['kebab-case']");
	}

	@Test
	void stopsAtTheDepthLimit() {
		String deep = "{\"a\":".repeat(ResponseSchema.MAX_DEPTH + 5) + "1" + "}".repeat(ResponseSchema.MAX_DEPTH + 5);

		ResponseSchema schema = ResponseSchema.of(JSON, deep).orElseThrow();

		assertThat(schema.complete()).isFalse();
		assertThat(schema.fields()).hasSize(ResponseSchema.MAX_DEPTH + 1);
	}

	@Test
	void stopsAtTheFieldLimit() {
		ResponseSchema schema = ResponseSchema.of(JSON, manyFields()).orElseThrow();

		assertThat(schema.complete()).isFalse();
		assertThat(schema.fields()).hasSize(ResponseSchema.MAX_FIELDS);
	}

	@Test
	void keepsAddingTypesToRecordedPathsAfterTheLimit() {
		// 한도를 넘긴 뒤에도 이미 기록한 경로에 새로 관측한 타입은 추가
		String body = "[" + manyFields() + ", {\"g0\": {\"k0\": \"text\"}}]";

		ResponseSchema schema = ResponseSchema.of(JSON, body).orElseThrow();

		assertThat(schema.complete()).isFalse();
		assertThat(schema.fields().get("$[].g0.k0")).containsExactly("number", "string");
	}

	/** 경로 1,231개. 한 객체의 키는 50개 이하라 동적 키 축약 없이 경로 수만 많음 */
	private static String manyFields() {
		return IntStream.range(0, 30)
			.mapToObj(group -> IntStream.range(0, 40)
				.mapToObj(i -> "\"k" + i + "\": 1")
				.collect(Collectors.joining(", ", "\"g" + group + "\": {", "}")))
			.collect(Collectors.joining(", ", "{", "}"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "application/json", "application/json; charset=UTF-8", "application/problem+json",
			"APPLICATION/JSON" })
	void readsJsonContentTypes(String contentType) {
		assertThat(ResponseSchema.of(contentType, "{\"a\": 1}")).isPresent();
	}

	@Test
	void skipsResponsesThatAreNotJson() {
		assertThat(ResponseSchema.of("text/html", "{\"a\": 1}")).isEmpty();
		assertThat(ResponseSchema.of(JSON, "not json")).isEmpty();
		assertThat(ResponseSchema.of(JSON, "{\"a\": 1} trailing")).isEmpty();
		assertThat(ResponseSchema.of(JSON, "")).isEmpty();
		assertThat(ResponseSchema.of(JSON, null)).isEmpty();
		assertThat(ResponseSchema.of(JSON, " ".repeat(10) + "x".repeat(ResponseSchema.MAX_BODY_LENGTH))).isEmpty();
	}

	@Test
	void guessesJsonFromTheBodyWhenTheContentTypeIsMissing() {
		assertThat(ResponseSchema.of(null, "  [1]")).isPresent();
		assertThat(ResponseSchema.of("", "{\"a\": 1}")).isPresent();
		assertThat(ResponseSchema.of(null, "\"just a string\"")).isEmpty();
	}

	@Test
	void sortsPathsTheSameWayWhateverTheInputOrder() {
		ResponseSchema first = ResponseSchema.of(JSON, "{\"b\": 1, \"a\": 2}").orElseThrow();
		ResponseSchema second = new ResponseSchema(Map.of("$.b", List.of("number"), "$", List.of("object"), "$.a",
				List.of("number")), true);

		assertThat(first).isEqualTo(second);
		assertThat(second.fields().keySet()).containsExactly("$", "$.a", "$.b");
	}

}
