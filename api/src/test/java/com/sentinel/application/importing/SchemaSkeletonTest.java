package com.sentinel.application.importing;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaSkeletonTest {

	private final JsonMapper json = JsonMapper.builder().build();

	@Test
	void stopsNestingAtTheDepthLimit() {
		// 8단계 중첩 객체. 한도(6) 이후는 빈 객체
		String schema = "{\"type\": \"string\"}";
		for (int i = 0; i < 8; i++) {
			schema = "{\"type\": \"object\", \"properties\": {\"child\": " + schema + "}}";
		}

		JsonNode built = new SchemaSkeleton(json.createObjectNode()).build(json.readTree(schema));

		assertThat(built.toString())
			.isEqualTo("{\"child\":{\"child\":{\"child\":{\"child\":{\"child\":{\"child\":{}}}}}}}");
	}

	@Test
	void fillsBooleansNullsAndNullableTypesAndStopsArraysAtTheDepthLimit() {
		JsonNode built = new SchemaSkeleton(json.createObjectNode()).build(json.readTree("""
				{"type": "object", "properties": {
				  "active": {"type": "boolean"},
				  "nothing": {"type": "null"},
				  "onlyNull": {"type": ["null"]},
				  "tags": {"items": {"type": "string"}}
				}}"""));

		// type 없이 items만 있으면 배열로 추정. 종류가 null뿐이면 null
		assertThat(built.toString())
			.isEqualTo("{\"active\":false,\"nothing\":null,\"onlyNull\":null,\"tags\":[\"string\"]}");

		String nested = "{\"type\": \"string\"}";
		for (int i = 0; i < 8; i++) {
			nested = "{\"type\": \"array\", \"items\": " + nested + "}";
		}
		assertThat(new SchemaSkeleton(json.createObjectNode()).build(json.readTree(nested)).toString())
			.isEqualTo("[[[[[[[]]]]]]]");
	}

	@Test
	void keepsAPropertyWhoseReferenceCannotBeFollowedForTheReadOnlyCheck() {
		JsonNode built = new SchemaSkeleton(json.readTree("{\"components\": {}}")).build(json.readTree("""
				{"type": "object", "properties": {
				  "id": {"$ref": "#/components/schemas/Missing"},
				  "name": {"type": "string"}
				}}"""));

		// 읽기 전용인지 알 수 없는 참조는 값도 만들 수 없어 빠지지만, 다른 속성은 그대로
		assertThat(built.toString()).isEqualTo("{\"name\":\"string\"}");
	}

	@Test
	void returnsNothingWhenTheSchemaSaysNothing() {
		SchemaSkeleton skeleton = new SchemaSkeleton(json.readTree("{\"components\": {}}"));

		assertThat(skeleton.build(null)).isNull();
		assertThat(skeleton.build(json.readTree("{}"))).isNull();
		assertThat(skeleton.build(json.readTree("{\"$ref\": \"#/components/schemas/Missing\"}"))).isNull();
		assertThat(skeleton.build(json.readTree("{\"$ref\": \"other.yaml#/Pet\"}"))).isNull();
	}

	@Test
	void usesTheFirstValueWhenAllOfMixesNonObjects() {
		JsonNode built = new SchemaSkeleton(json.createObjectNode())
			.build(json.readTree("{\"allOf\": [{\"type\": \"integer\", \"minimum\": 1}, {\"maximum\": 9}]}"));

		assertThat(built.asInt()).isZero();
	}

}
