package com.sentinel.application.importing;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * JSON 스키마로 요청 본문 뼈대 생성. 값은 자리 표시용이며 사용자가 실제 값으로 바꾸는 것을 전제로 함.
 * <p>
 * 규칙
 * <ul>
 * <li>스키마에 {@code example} → {@code default} → {@code const} → {@code enum} 첫 값이 있으면 그대로 사용</li>
 * <li>객체는 {@code readOnly}를 제외한 모든 속성, 배열은 항목 1개</li>
 * <li>{@code allOf}는 객체 병합, {@code oneOf}·{@code anyOf}는 첫 스키마</li>
 * <li>문자열은 {@code format}별 자리 표시 값, 숫자는 0, 불리언은 false</li>
 * <li>{@code #/...} 내부 참조만 해석. 순환 참조와 {@link #MAX_DEPTH} 초과 깊이는 생략</li>
 * </ul>
 */
final class SchemaSkeleton {

	/** 중첩 객체·배열 최대 깊이. 큰 스키마에서 본문이 과도하게 커지는 것 방지 */
	static final int MAX_DEPTH = 6;

	private static final int MAX_REF_DEPTH = 10;

	private static final Map<String, String> STRING_FORMATS = Map.of("date-time", "2026-01-01T00:00:00Z", "date",
			"2026-01-01", "time", "00:00:00", "email", "user@example.com", "uuid",
			"00000000-0000-0000-0000-000000000000", "uri", "https://example.com", "url", "https://example.com",
			"hostname", "example.com", "ipv4", "127.0.0.1");

	private final JsonNode root;

	private final JsonNodeFactory nodes = JsonNodeFactory.instance;

	SchemaSkeleton(JsonNode root) {
		this.root = root;
	}

	/**
	 * @return 뼈대. 스키마로 값을 정할 수 없으면 null
	 */
	JsonNode build(JsonNode schema) {
		return build(schema, 0, new HashSet<>());
	}

	private JsonNode build(JsonNode schema, int depth, Set<String> refsInProgress) {
		if (schema == null || !schema.isObject()) {
			return null;
		}
		if (schema.has("$ref")) {
			String ref = schema.get("$ref").asString();
			// 자기 자신을 다시 참조하는 스키마(트리 구조 등)는 해당 위치를 생략
			if (!ref.startsWith("#/") || !refsInProgress.add(ref)) {
				return null;
			}
			JsonNode target = root.at(ref.substring(1));
			JsonNode built = target.isMissingNode() ? null : build(target, depth, refsInProgress);
			refsInProgress.remove(ref);
			return built;
		}
		for (String given : new String[] { "example", "default", "const" }) {
			if (schema.has(given)) {
				return schema.get(given);
			}
		}
		JsonNode values = schema.get("enum");
		if (values != null && values.isArray() && !values.isEmpty()) {
			return values.get(0);
		}
		JsonNode allOf = schema.get("allOf");
		if (allOf != null && allOf.isArray() && !allOf.isEmpty()) {
			return merge(allOf, depth, refsInProgress);
		}
		for (String choice : new String[] { "oneOf", "anyOf" }) {
			JsonNode options = schema.get(choice);
			if (options != null && options.isArray() && !options.isEmpty()) {
				return build(options.get(0), depth, refsInProgress);
			}
		}
		return switch (type(schema)) {
			case "object" -> object(schema, depth, refsInProgress);
			case "array" -> array(schema, depth, refsInProgress);
			case "string" -> nodes.stringNode(STRING_FORMATS.getOrDefault(
					schema.path("format").asString("").toLowerCase(Locale.ROOT), "string"));
			case "integer", "number" -> nodes.numberNode(0);
			case "boolean" -> nodes.booleanNode(false);
			case "null" -> nodes.nullNode();
			default -> null;
		};
	}

	private ObjectNode object(JsonNode schema, int depth, Set<String> refsInProgress) {
		ObjectNode object = nodes.objectNode();
		if (depth >= MAX_DEPTH) {
			return object;
		}
		for (Map.Entry<String, JsonNode> property : schema.path("properties").properties()) {
			// 응답 전용 속성은 요청 본문에서 제외
			if (deref(property.getValue()).path("readOnly").asBoolean(false)) {
				continue;
			}
			JsonNode value = build(property.getValue(), depth + 1, refsInProgress);
			if (value != null) {
				object.set(property.getKey(), value);
			}
		}
		return object;
	}

	private ArrayNode array(JsonNode schema, int depth, Set<String> refsInProgress) {
		ArrayNode array = nodes.arrayNode();
		if (depth >= MAX_DEPTH) {
			return array;
		}
		JsonNode item = build(schema.get("items"), depth + 1, refsInProgress);
		if (item != null) {
			array.add(item);
		}
		return array;
	}

	/**
	 * allOf 병합. 객체끼리는 속성을 합치고, 객체가 아닌 스키마가 섞이면 첫 값 사용
	 */
	private JsonNode merge(JsonNode allOf, int depth, Set<String> refsInProgress) {
		ObjectNode merged = nodes.objectNode();
		JsonNode firstValue = null;
		for (JsonNode part : allOf) {
			JsonNode value = build(part, depth, refsInProgress);
			if (value instanceof ObjectNode object) {
				merged.setAll(object);
			}
			else if (value != null && firstValue == null) {
				firstValue = value;
			}
		}
		return merged.isEmpty() && firstValue != null ? firstValue : merged;
	}

	/**
	 * 스키마 종류. OpenAPI 3.1의 {@code type: [string, "null"]}은 null이 아닌 첫 종류, 생략 시 구조로 추정
	 */
	private static String type(JsonNode schema) {
		JsonNode type = schema.get("type");
		if (type != null && type.isArray()) {
			for (JsonNode candidate : type) {
				if (!"null".equals(candidate.asString())) {
					return candidate.asString();
				}
			}
			return "null";
		}
		if (type != null && type.isString()) {
			return type.asString();
		}
		if (schema.has("properties")) {
			return "object";
		}
		return schema.has("items") ? "array" : "";
	}

	/** readOnly 확인용 참조 따라가기. 해석 실패 시 원래 노드 */
	private JsonNode deref(JsonNode schema) {
		JsonNode current = schema;
		for (int i = 0; i < MAX_REF_DEPTH && current != null && current.has("$ref"); i++) {
			String ref = current.get("$ref").asString();
			if (!ref.startsWith("#/") || root.at(ref.substring(1)).isMissingNode()) {
				return current;
			}
			current = root.at(ref.substring(1));
		}
		return current == null ? nodes.missingNode() : current;
	}

}
