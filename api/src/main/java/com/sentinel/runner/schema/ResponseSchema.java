package com.sentinel.runner.schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 응답 JSON의 구조 지문. 값은 버리고 경로별로 관측한 타입만 기록 (스키마 드리프트 감지의 기준 데이터)
 * <p>
 * 규칙 (docs/design.md "스키마 드리프트 감지")
 * <ul>
 * <li>경로는 평탄화: {@code $}, {@code $.items}, {@code $.items[]}, {@code $.items[].id}. 식별자가 아닌 키는 {@code $['a.b']}</li>
 * <li>타입: object, array, string, number, boolean, null. 한 경로에 여러 타입이 관측되면 모두 기록</li>
 * <li>배열은 모든 요소의 타입을 합쳐 {@code []} 하나로 표현. 빈 배열은 요소 경로 없음</li>
 * <li>동적 키(ID·날짜를 키로 쓰는 객체)는 {@code *}로 축약: 형제 키가 {@value #MAX_SIBLING_KEYS}개를 넘거나,
 * 2개 이상이면서 모두 숫자·UUID·날짜 형태</li>
 * <li>깊이 {@value #MAX_DEPTH}, 경로 {@value #MAX_FIELDS}개까지 기록. 넘으면 {@code complete=false}</li>
 * </ul>
 *
 * @param fields 경로 → 관측 타입 (경로·타입 모두 정렬)
 * @param complete 모든 경로를 기록했는지. 깊이·경로 수 한도를 넘으면 false
 */
public record ResponseSchema(Map<String, List<String>> fields, boolean complete) {

	static final int MAX_DEPTH = 20;

	static final int MAX_FIELDS = 1000;

	static final int MAX_SIBLING_KEYS = 50;

	/** 파싱할 본문 최대 길이(문자). 넘으면 지문 생략 */
	static final int MAX_BODY_LENGTH = 5 * 1024 * 1024;

	/** JSON 뒤에 다른 내용이 붙은 본문은 JSON으로 보지 않음 (Jackson 3 기본값이지만 의도를 명시) */
	private static final ObjectMapper JSON = JsonMapper.builder()
		.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
		.build();

	private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

	private static final Pattern DYNAMIC_KEY = Pattern.compile(
			"\\d+|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}|\\d{4}-\\d{2}-\\d{2}.*");

	/** 저장 후 다시 읽어도 같은 순서가 되도록 경로 정렬 */
	public ResponseSchema {
		Map<String, List<String>> sorted = new TreeMap<>();
		fields.forEach((path, types) -> sorted.put(path, List.copyOf(types)));
		fields = Collections.unmodifiableMap(sorted);
	}

	/**
	 * @param contentType 응답 Content-Type. null 허용
	 * @return JSON 응답이면 지문. JSON이 아니거나 해석할 수 없거나 너무 크면 빈 값
	 */
	public static Optional<ResponseSchema> of(String contentType, String body) {
		if (body == null || body.isBlank() || body.length() > MAX_BODY_LENGTH) {
			return Optional.empty();
		}
		if (!isJson(contentType, body)) {
			return Optional.empty();
		}
		JsonNode root;
		try {
			root = JSON.readTree(body);
		}
		catch (JacksonException ex) {
			return Optional.empty();
		}
		Collector collector = new Collector();
		collector.visit(root, "$", 0);
		return Optional.of(collector.toSchema());
	}

	/** Content-Type이 JSON(application/json, +json)이거나, 형식이 없을 때 본문이 객체·배열로 시작 */
	private static boolean isJson(String contentType, String body) {
		if (contentType != null && !contentType.isBlank()) {
			String type = contentType.split(";", 2)[0].strip().toLowerCase();
			return type.equals("application/json") || type.endsWith("+json");
		}
		String trimmed = body.stripLeading();
		return trimmed.startsWith("{") || trimmed.startsWith("[");
	}

	private static final class Collector {

		private final Map<String, SortedSet<String>> fields = new TreeMap<>();

		private boolean complete = true;

		void visit(JsonNode node, String path, int depth) {
			if (!record(path, typeOf(node))) {
				return;
			}
			if (depth >= MAX_DEPTH && (node.isObject() || node.isArray()) && !node.isEmpty()) {
				complete = false;
				return;
			}
			if (node.isArray()) {
				for (JsonNode element : node) {
					visit(element, path + "[]", depth + 1);
				}
			}
			else if (node.isObject()) {
				List<Map.Entry<String, JsonNode>> properties = new ArrayList<>(node.properties());
				boolean dynamic = isDynamic(properties);
				for (Map.Entry<String, JsonNode> property : properties) {
					visit(property.getValue(), dynamic ? path + ".*" : child(path, property.getKey()), depth + 1);
				}
			}
		}

		/** @return 기록했는지. 경로 수 한도를 넘는 새 경로는 기록하지 않고 하위도 건너뜀 */
		private boolean record(String path, String type) {
			SortedSet<String> types = fields.get(path);
			if (types == null) {
				if (fields.size() >= MAX_FIELDS) {
					complete = false;
					return false;
				}
				types = new TreeSet<>();
				fields.put(path, types);
			}
			types.add(type);
			return true;
		}

		private static boolean isDynamic(List<Map.Entry<String, JsonNode>> properties) {
			if (properties.size() > MAX_SIBLING_KEYS) {
				return true;
			}
			return properties.size() >= 2
					&& properties.stream().allMatch(property -> DYNAMIC_KEY.matcher(property.getKey()).matches());
		}

		private static String child(String path, String key) {
			if (IDENTIFIER.matcher(key).matches()) {
				return path + "." + key;
			}
			return path + "['" + key.replace("\\", "\\\\").replace("'", "\\'") + "']";
		}

		private static String typeOf(JsonNode node) {
			return switch (node.getNodeType()) {
				case OBJECT, POJO -> "object";
				case ARRAY -> "array";
				case STRING, BINARY -> "string";
				case NUMBER -> "number";
				case BOOLEAN -> "boolean";
				case NULL, MISSING -> "null";
			};
		}

		ResponseSchema toSchema() {
			Map<String, List<String>> result = new TreeMap<>();
			fields.forEach((path, types) -> result.put(path, List.copyOf(types)));
			return new ResponseSchema(result, complete);
		}

	}

}
