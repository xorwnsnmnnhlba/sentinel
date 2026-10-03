package com.sentinel.application.importing;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sentinel.application.CatalogQueryService.Request;
import com.sentinel.application.InvalidRequestException;
import com.sentinel.domain.RequestSpec;
import com.sentinel.runner.HttpMethod;
import com.sentinel.runner.assertion.Assertion;
import com.sentinel.runner.assertion.StatusCodeAssertion;
import tools.jackson.databind.JsonNode;

import static com.sentinel.application.importing.ImportDocuments.format;
import static com.sentinel.application.importing.ImportDocuments.parse;
import static com.sentinel.application.importing.ImportDocuments.text;
import static com.sentinel.application.importing.ImportDocuments.truncateName;
import static com.sentinel.application.importing.ImportDocuments.uniqueName;
import static com.sentinel.application.importing.ImportDocuments.variable;

/**
 * OpenAPI 3 문서(JSON·YAML)를 케이스 초안으로 변환. 저장하지 않으며 상태 없음.
 * <p>
 * 변환 규칙
 * <ul>
 * <li>경로 × 메서드마다 케이스 1건. {@code trace}는 미지원 메서드라 제외하고 경고</li>
 * <li>경로 파라미터 {@code {petId}}는 변수 {@code {{petId}}}로, 필수 query·header 파라미터는 변수로 채움. 선택 파라미터는 생략</li>
 * <li>JSON 요청 본문은 예시({@code example}, {@code examples}), 없으면 스키마로 만든 뼈대({@link SchemaSkeleton})</li>
 * <li>보안 스킴은 인증 헤더·query로 변환하고 값은 스킴 이름의 변수로 채움 (예: {@code Authorization: Bearer {{bearerAuth}}})</li>
 * <li>판정은 선언된 2xx 응답 코드({@code 2XX}는 {@code 2xx}). 없으면 {@code 2xx}</li>
 * <li>{@code #/...} 내부 참조만 해석. 외부 파일 참조는 경고 후 생략</li>
 * <li>{@code servers}는 기본 URL을 환경에서 관리하므로 케이스에 넣지 않고 참고용으로만 반환</li>
 * </ul>
 */
final class OpenApiConverter {

	/** 참조가 참조를 가리키는 경우의 최대 추적 횟수. 순환 참조 차단 */
	private static final int MAX_REF_DEPTH = 10;

	private static final List<String> METHODS = List.of("get", "put", "post", "delete", "options", "head", "patch",
			"trace");

	/** OpenAPI 규칙상 header 파라미터로 정의해도 무시하는 이름. 본문 미디어 타입·보안 스킴으로 정해짐 */
	private static final Set<String> IGNORED_HEADER_PARAMETERS = Set.of("accept", "content-type", "authorization");

	private static final Pattern PATH_PARAMETER = Pattern.compile("\\{([^{}]+)}");

	private static final Pattern RESPONSE_CODE = Pattern.compile("[1-5][0-9xX]{2}");

	/**
	 * @throws InvalidRequestException 해석할 수 없는 문서, OpenAPI 3이 아닌 문서, 작업이 없는 문서
	 */
	public ConvertedDocument convert(String content) {
		JsonNode root = parse(content);
		requireOpenApi3(root);
		JsonNode paths = root.path("paths");
		if (!paths.isObject() || paths.isEmpty()) {
			throw new InvalidRequestException("document defines no paths");
		}

		List<String> warnings = new ArrayList<>();
		List<CaseDraft> drafts = new ArrayList<>();
		Set<String> usedNames = new HashSet<>();
		// 보안 스킴별 안내. 작업마다 반복하지 않도록 문서 단위로 한 번씩
		Map<String, String> securityNotes = new LinkedHashMap<>();
		for (Map.Entry<String, JsonNode> pathEntry : paths.properties()) {
			String path = pathEntry.getKey();
			JsonNode pathItem = resolve(root, pathEntry.getValue(), path, warnings);
			if (pathItem == null || !pathItem.isObject()) {
				continue;
			}
			for (String method : METHODS) {
				JsonNode operation = pathItem.get(method);
				if (operation == null || !operation.isObject()) {
					continue;
				}
				String source = method.toUpperCase(Locale.ROOT) + " " + path;
				if (method.equals("trace")) {
					warnings.add(source + ": TRACE is not supported and was skipped");
					continue;
				}
				CaseDraft draft = convertOperation(root, path, HttpMethod.valueOf(method.toUpperCase(Locale.ROOT)),
						pathItem, operation, source, securityNotes);
				drafts.add(draft.withName(uniqueName(draft.name(), usedNames)));
			}
		}
		if (drafts.isEmpty()) {
			throw new InvalidRequestException("document defines no supported operations");
		}
		warnings.addAll(securityNotes.values());

		JsonNode info = root.path("info");
		List<String> servers = new ArrayList<>();
		root.path("servers").forEach(server -> {
			String url = text(server.get("url"));
			if (url != null) {
				servers.add(url);
			}
		});
		return new ConvertedDocument(text(info.get("title")), text(info.get("version")), servers, drafts, warnings);
	}

	private static void requireOpenApi3(JsonNode root) {
		if (root == null || !root.isObject()) {
			throw new InvalidRequestException("document must be a JSON or YAML object");
		}
		String swagger = text(root.get("swagger"));
		if (swagger != null) {
			throw new InvalidRequestException(
					"only OpenAPI 3 documents are supported (found swagger " + swagger + ")");
		}
		String version = text(root.get("openapi"));
		if (version == null || !version.startsWith("3.")) {
			throw new InvalidRequestException("only OpenAPI 3 documents are supported (found openapi "
					+ (version == null ? "none" : version) + ")");
		}
	}

	private CaseDraft convertOperation(JsonNode root, String path, HttpMethod method, JsonNode pathItem,
			JsonNode operation, String source, Map<String, String> securityNotes) {
		List<String> warnings = new ArrayList<>();
		if (operation.path("deprecated").asBoolean(false)) {
			warnings.add("operation is deprecated");
		}

		Map<String, JsonNode> parameters = parameters(root, pathItem, operation, warnings);
		StringBuilder query = new StringBuilder();
		Map<String, List<String>> headers = new LinkedHashMap<>();
		for (JsonNode parameter : parameters.values()) {
			if (!parameter.path("required").asBoolean(false)) {
				continue;
			}
			String name = text(parameter.get("name"));
			String location = text(parameter.get("in"));
			if (name == null || location == null) {
				continue;
			}
			switch (location) {
				case "query" -> query.append(query.isEmpty() ? '?' : '&')
					.append(name)
					.append('=')
					.append(variable(name, warnings));
				case "header" -> {
					if (!IGNORED_HEADER_PARAMETERS.contains(name.toLowerCase(Locale.ROOT))) {
						headers.put(name, List.of(variable(name, warnings)));
					}
				}
				case "cookie" -> warnings.add("required cookie parameter '" + name + "' is not supported");
				default -> {
					// path 파라미터는 경로 치환에서 처리
				}
			}
		}

		applySecurity(root, operation, headers, query, warnings, securityNotes);
		String body = requestBody(root, operation, headers, warnings);
		Request request = new Request(method, pathWithVariables(path, warnings) + query, headers, body);
		List<Assertion> assertions = List.of(new StatusCodeAssertion(successCodes(operation, warnings)));
		return new CaseDraft(source, name(operation, source), request, assertions, List.copyOf(warnings));
	}

	/**
	 * 경로 공통 파라미터와 작업 파라미터 병합. 같은 이름·위치는 작업 쪽 우선 (OpenAPI 규칙)
	 */
	private Map<String, JsonNode> parameters(JsonNode root, JsonNode pathItem, JsonNode operation,
			List<String> warnings) {
		Map<String, JsonNode> merged = new LinkedHashMap<>();
		for (JsonNode source : List.of(pathItem.path("parameters"), operation.path("parameters"))) {
			for (JsonNode node : source) {
				JsonNode parameter = resolve(root, node, "parameter", warnings);
				if (parameter != null && parameter.isObject()) {
					merged.put(text(parameter.get("in")) + ":" + text(parameter.get("name")), parameter);
				}
			}
		}
		return merged;
	}

	/**
	 * 보안 스킴을 요청에 반영. 작업의 {@code security}, 없으면 문서의 {@code security} 사용. 대안이 여럿이면 첫 번째,
	 * 빈 목록({@code security: []})은 인증 없음. 값은 스킴 이름의 변수로 채워 환경 시크릿으로 지정하도록 함
	 */
	private void applySecurity(JsonNode root, JsonNode operation, Map<String, List<String>> headers,
			StringBuilder query, List<String> warnings, Map<String, String> securityNotes) {
		JsonNode security = operation.has("security") ? operation.get("security") : root.get("security");
		if (security == null || !security.isArray() || security.isEmpty()) {
			return;
		}
		for (String schemeName : security.get(0).propertyNames()) {
			JsonNode scheme = resolve(root, root.path("components").path("securitySchemes").get(schemeName),
					"security scheme", warnings);
			if (scheme == null || !scheme.isObject()) {
				warnings.add("security scheme '" + schemeName + "' is not defined");
				continue;
			}
			String variable = variable(schemeName, warnings);
			String type = String.valueOf(text(scheme.get("type")));
			String location = text(scheme.get("in"));
			String keyName = text(scheme.get("name"));
			String applied = switch (type) {
				case "http" -> switch (String.valueOf(text(scheme.get("scheme"))).toLowerCase(Locale.ROOT)) {
					case "bearer" -> authorization(headers, "Bearer " + variable);
					case "basic" -> authorization(headers, "Basic " + variable);
					default -> null;
				};
				case "oauth2", "openIdConnect" -> authorization(headers, "Bearer " + variable);
				case "apiKey" -> {
					if (keyName == null || "cookie".equals(location)) {
						yield null;
					}
					if ("query".equals(location)) {
						query.append(query.isEmpty() ? '?' : '&').append(keyName).append('=').append(variable);
						yield "query " + keyName + "=" + variable;
					}
					headers.put(keyName, List.of(variable));
					yield "'" + keyName + ": " + variable + "'";
				}
				default -> null;
			};
			if (applied == null) {
				warnings.add("security scheme '" + schemeName + "' (" + describe(scheme) + ") is not supported");
				continue;
			}
			securityNotes.putIfAbsent(schemeName, "security scheme '" + schemeName + "' is sent as " + applied
					+ "; add " + variable + " as a secret of the environment");
		}
	}

	private static String authorization(Map<String, List<String>> headers, String value) {
		headers.put("Authorization", List.of(value));
		return "'Authorization: " + value + "'";
	}

	private static String describe(JsonNode scheme) {
		String type = String.valueOf(text(scheme.get("type")));
		String detail = "http".equals(type) ? text(scheme.get("scheme")) : text(scheme.get("in"));
		return detail == null ? type : type + " " + detail;
	}

	private String requestBody(JsonNode root, JsonNode operation, Map<String, List<String>> headers,
			List<String> warnings) {
		JsonNode requestBody = resolve(root, operation.get("requestBody"), "request body", warnings);
		if (requestBody == null || !requestBody.isObject()) {
			return null;
		}
		JsonNode content = requestBody.path("content");
		String mediaType = null;
		for (Map.Entry<String, JsonNode> entry : content.properties()) {
			if (isJson(entry.getKey())) {
				mediaType = entry.getKey();
				break;
			}
		}
		boolean required = requestBody.path("required").asBoolean(false);
		if (mediaType == null) {
			if (!content.isEmpty()) {
				warnings.add("request body media type " + content.propertyNames() + " is not supported; body left empty");
			}
			return null;
		}
		JsonNode example = example(root, content.get(mediaType), warnings);
		if (example == null) {
			example = new SchemaSkeleton(root).build(content.path(mediaType).get("schema"));
			if (example == null) {
				if (required) {
					warnings.add("request body has no JSON example or schema; body left empty");
				}
				return null;
			}
			warnings.add("request body was generated from the schema; replace the placeholder values");
		}
		String body = format(example);
		if (body.length() > RequestSpec.MAX_BODY_LENGTH) {
			warnings.add("request body example exceeds " + RequestSpec.MAX_BODY_LENGTH + " characters; body left empty");
			return null;
		}
		headers.putIfAbsent("Content-Type", List.of(mediaType));
		return body;
	}

	/**
	 * 예시 우선순위: 미디어 타입의 {@code example} → {@code examples}의 첫 값 → 스키마의 {@code example}
	 */
	private JsonNode example(JsonNode root, JsonNode media, List<String> warnings) {
		if (media == null || !media.isObject()) {
			return null;
		}
		if (media.has("example")) {
			return media.get("example");
		}
		for (JsonNode entry : media.path("examples")) {
			JsonNode example = resolve(root, entry, "example", warnings);
			if (example != null && example.has("value")) {
				return example.get("value");
			}
			if (example != null && example.has("externalValue")) {
				warnings.add("external example '" + text(example.get("externalValue")) + "' is not supported");
			}
		}
		JsonNode schema = resolve(root, media.get("schema"), "schema", warnings);
		return schema != null && schema.has("example") ? schema.get("example") : null;
	}

	private static List<String> successCodes(JsonNode operation, List<String> warnings) {
		List<String> codes = new ArrayList<>();
		for (String code : operation.path("responses").propertyNames()) {
			if (RESPONSE_CODE.matcher(code).matches() && code.startsWith("2")) {
				codes.add(code.toLowerCase(Locale.ROOT));
			}
		}
		if (codes.isEmpty()) {
			warnings.add("no 2xx response is declared; expecting any 2xx");
			return List.of("2xx");
		}
		return codes;
	}

	private static String pathWithVariables(String path, List<String> warnings) {
		Matcher matcher = PATH_PARAMETER.matcher(path);
		StringBuilder converted = new StringBuilder();
		while (matcher.find()) {
			matcher.appendReplacement(converted, Matcher.quoteReplacement(variable(matcher.group(1), warnings)));
		}
		matcher.appendTail(converted);
		return converted.toString();
	}

	private static String name(JsonNode operation, String source) {
		String name = text(operation.get("summary"));
		if (name == null) {
			name = text(operation.get("operationId"));
		}
		if (name == null) {
			name = source;
		}
		return truncateName(name);
	}

	/**
	 * {@code $ref} 해석. 내부 참조({@code #/...})만 지원하며 해석 실패 시 경고 후 null
	 */
	private static JsonNode resolve(JsonNode root, JsonNode node, String what, List<String> warnings) {
		JsonNode current = node;
		for (int depth = 0; current != null && current.has("$ref"); depth++) {
			String ref = text(current.get("$ref"));
			if (depth >= MAX_REF_DEPTH || ref == null) {
				warnings.add(what + ": reference '" + ref + "' could not be resolved");
				return null;
			}
			if (!ref.startsWith("#/")) {
				warnings.add(what + ": external reference '" + ref + "' is not supported");
				return null;
			}
			JsonNode target = root.at(ref.substring(1));
			if (target.isMissingNode()) {
				warnings.add(what + ": reference '" + ref + "' was not found");
				return null;
			}
			current = target;
		}
		return current;
	}

	private static boolean isJson(String mediaType) {
		String type = mediaType.toLowerCase(Locale.ROOT).split(";")[0].strip();
		return type.equals("application/json") || type.endsWith("+json");
	}

}
