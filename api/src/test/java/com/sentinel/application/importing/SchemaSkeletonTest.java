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
