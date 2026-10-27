package com.sentinel.runner.schema;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.sentinel.runner.schema.SchemaTolerance.Kind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaToleranceTest {

	private static ResponseSchema schema(String json) {
		return ResponseSchema.of("application/json", json).orElseThrow();
	}

	private static List<ResponseSchema> schemas(String... jsons) {
		return Arrays.stream(jsons).map(SchemaToleranceTest::schema).toList();
	}

	@Test
	void remembersPathsThatWereNull() {
		assertThat(SchemaTolerance.observedIn(schemas("{\"id\": 1, \"name\": null}", "{\"id\": 2, \"name\": \"a\"}")))
			.containsExactly(new SchemaTolerance("$.name", Kind.NULLABLE));
	}

	@Test
	void remembersFieldsThatWentMissingAfterAppearing() {
		assertThat(SchemaTolerance.observedIn(schemas("{\"id\": 1, \"note\": \"a\"}", "{\"id\": 2}")))
			.containsExactly(new SchemaTolerance("$.note", Kind.OPTIONAL));
	}

	@Test
	void doesNotCountTheAbsenceBeforeANewFieldFirstAppears() {
		// 새로 생긴 필드는 창이 채워지면 필수가 될 수 있어야 함
		assertThat(SchemaTolerance.observedIn(schemas("{\"id\": 1}", "{\"id\": 2, \"note\": \"a\"}"))).isEmpty();
	}

	@Test
	void skipsAbsencesThatAreNotRemovals() {
		// 빈 배열의 요소, 상위가 없거나 null인 하위, 일부만 기록한 지문
		assertThat(SchemaTolerance.observedIn(schemas("{\"items\": [{\"id\": 1}]}", "{\"items\": []}"))).isEmpty();
		assertThat(SchemaTolerance.observedIn(schemas("{\"profile\": {\"age\": 1}}", "{\"profile\": null}")))
			.containsExactly(new SchemaTolerance("$.profile", Kind.NULLABLE));
		assertThat(SchemaTolerance.observedIn(List.of(schema("{\"id\": 1}"),
				new ResponseSchema(Map.of("$", List.of("object")), false))))
			.isEmpty();
	}

	@Test
	void neverTreatsTheRootAsMissing() {
		// 루트는 상위가 없음. 직접 만든 지문에서 루트가 빠져도 오류 없이 넘어감
		assertThat(SchemaTolerance.observedIn(List.of(schema("{}"), new ResponseSchema(Map.of(), true)))).isEmpty();
	}

	@Test
	void reportsOnlyTheTopOfARemovedObject() {
		assertThat(SchemaTolerance.observedIn(schemas("{\"profile\": {\"age\": 1}}", "{}")))
			.containsExactly(new SchemaTolerance("$.profile", Kind.OPTIONAL));
	}

	@Test
	void keepsOtherTypeChangesOutOfTolerances() {
		// 타입이 바뀐 뒤 창 밖으로 밀려나 되돌아오면 다시 보고되어야 함
		assertThat(SchemaTolerance.observedIn(schemas("{\"id\": 1}", "{\"id\": \"a\"}"))).isEmpty();
	}

}
