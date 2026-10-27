package com.sentinel.runner.schema;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.sentinel.runner.schema.SchemaDrift.Change;
import com.sentinel.runner.schema.SchemaDrift.Kind;
import com.sentinel.runner.schema.SchemaDrift.Level;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class SchemaDriftTest {

	private static ResponseSchema schema(String json) {
		return ResponseSchema.of("application/json", json).orElseThrow();
	}

	private static SchemaBaseline baseline(String... jsons) {
		return SchemaBaseline.of(Arrays.stream(jsons).map(SchemaDriftTest::schema).toList());
	}

	private static List<Change> drift(SchemaBaseline baseline, String json) {
		return SchemaDrift.compare(baseline, schema(json));
	}

	@Test
	void findsNothingWhenTheStructureIsTheSame() {
		SchemaBaseline baseline = baseline("{\"id\": 1, \"name\": \"a\"}", "{\"id\": 2, \"name\": \"b\"}");

		assertThat(drift(baseline, "{\"id\": 3, \"name\": \"c\"}")).isEmpty();
	}

	@Test
	void judgesRequiredFieldsByPresenceWhereTheParentWasPresent() {
		SchemaBaseline baseline = baseline("{\"items\": [{\"id\": 1, \"tag\": \"a\"}]}", "{\"items\": []}",
				"{\"items\": [{\"id\": 2}]}");

		// 빈 배열인 응답은 요소 필드의 관측에서 제외
		assertThat(baseline.fields().get("$.items[].id")).isEqualTo(new SchemaBaseline.Field(2, 2, List.of("number")));
		assertThat(baseline.fields().get("$.items[].id").required()).isTrue();
		assertThat(baseline.fields().get("$.items[].tag").required()).isFalse();
		assertThat(baseline.fields().get("$.items").required()).isTrue();
	}

	@Test
	void reportsMissingRequiredFieldsAsBreaking() {
		SchemaBaseline baseline = baseline("{\"id\": 1, \"note\": \"x\"}", "{\"id\": 2}");

		assertThat(drift(baseline, "{\"note\": \"y\"}"))
			.containsExactly(new Change(Level.BREAKING, Kind.REMOVED, "$.id", List.of("number"), List.of()));
		// 선택 필드(note)가 없는 것은 변화가 아님
		assertThat(drift(baseline, "{\"id\": 3}")).isEmpty();
	}

	@Test
	void reportsNewNonNullTypesAsBreakingAndNullAsWarning() {
		SchemaBaseline baseline = baseline("{\"id\": 1, \"name\": \"a\"}", "{\"id\": 2, \"name\": \"b\"}");

		assertThat(drift(baseline, "{\"id\": \"3\", \"name\": null}")).containsExactly(
				new Change(Level.BREAKING, Kind.TYPE_CHANGED, "$.id", List.of("number"), List.of("string")),
				new Change(Level.WARNING, Kind.NULLABLE, "$.name", List.of("string"), List.of("null")));
	}

	@Test
	void keepsNullAllowedOnceObserved() {
		SchemaBaseline baseline = baseline("{\"name\": \"a\"}", "{\"name\": null}");

		assertThat(baseline.fields().get("$.name").types()).containsExactly("null", "string");
		assertThat(drift(baseline, "{\"name\": null}")).isEmpty();
	}

	@Test
	void reportsNewFieldsAsInfoOnlyAtTheirTop() {
		SchemaBaseline baseline = baseline("{\"id\": 1}");

		assertThat(drift(baseline, "{\"id\": 1, \"meta\": {\"page\": 1, \"size\": 20}}"))
			.containsExactly(new Change(Level.INFO, Kind.ADDED, "$.meta", List.of(), List.of("object")));
	}

	@Test
	void treatsEmptyArraysAndDynamicMapsAsNormal() {
		SchemaBaseline baseline = baseline("{\"items\": [{\"id\": 1}], \"byDate\": {\"2026-09-01\": 1, \"2026-09-02\": 2}}");

		assertThat(drift(baseline, "{\"items\": [], \"byDate\": {}}")).isEmpty();
	}

	@Test
	void doesNotReportChildrenOfAParentThatBecameNullOrChangedType() {
		SchemaBaseline baseline = baseline("{\"profile\": {\"age\": 1, \"city\": \"a\"}}");

		// 상위가 null이 된 것만 경고, 하위 필드의 없음은 보고하지 않음
		assertThat(drift(baseline, "{\"profile\": null}")).containsExactly(
				new Change(Level.WARNING, Kind.NULLABLE, "$.profile", List.of("object"), List.of("null")));
		// 객체가 배열이 되면 타입 변경 한 건만 보고
		assertThat(drift(baseline, "{\"profile\": [{\"x\": 1}]}")).containsExactly(
				new Change(Level.BREAKING, Kind.TYPE_CHANGED, "$.profile", List.of("object"), List.of("array")));
	}

	@Test
	void reportsOnlyTheTopOfARemovedObject() {
		SchemaBaseline baseline = baseline("{\"profile\": {\"age\": 1}}");

		assertThat(drift(baseline, "{}"))
			.containsExactly(new Change(Level.BREAKING, Kind.REMOVED, "$.profile", List.of("object"), List.of()));
	}

	@Test
	void skipsRemovalChecksForIncompleteFingerprints() {
		SchemaBaseline baseline = baseline("{\"id\": 1}");
		ResponseSchema partial = new ResponseSchema(Map.of("$", List.of("object")), false);

		assertThat(SchemaDrift.compare(baseline, partial)).isEmpty();
	}

	@Test
	void ordersBySeverityThenPath() {
		SchemaBaseline baseline = baseline("{\"a\": 1, \"b\": \"x\", \"c\": true}");

		assertThat(drift(baseline, "{\"b\": null, \"c\": 1, \"d\": 1}")).extracting(Change::level, Change::path)
			.containsExactly(tuple(Level.BREAKING, "$.a"),
					tuple(Level.BREAKING, "$.c"),
					tuple(Level.WARNING, "$.b"),
					tuple(Level.INFO, "$.d"));
	}

	@Test
	void toleratesNullAndAbsenceRememberedOutsideTheWindow() {
		SchemaBaseline baseline = baseline("{\"id\": 1, \"name\": \"a\"}", "{\"id\": 2, \"name\": \"b\"}")
			.tolerating(List.of(new SchemaTolerance("$.name", SchemaTolerance.Kind.NULLABLE),
					new SchemaTolerance("$.id", SchemaTolerance.Kind.OPTIONAL),
					new SchemaTolerance("$.gone", SchemaTolerance.Kind.OPTIONAL)));

		assertThat(baseline.fields().get("$.name").types()).containsExactly("null", "string");
		assertThat(baseline.fields().get("$.id").required()).isFalse();
		// 기준선에 없는 경로의 허용은 무시
		assertThat(baseline.fields()).doesNotContainKey("$.gone");
		assertThat(drift(baseline, "{\"name\": null}")).isEmpty();
		// null 외 타입 변화는 그대로 보고
		assertThat(drift(baseline, "{\"id\": \"x\", \"name\": 1}")).extracting(Change::kind)
			.containsExactly(Kind.TYPE_CHANGED, Kind.TYPE_CHANGED);
	}

}
