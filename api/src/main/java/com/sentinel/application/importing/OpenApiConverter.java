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
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.exceptions.YamlEngineException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.core.util.Separators;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * OpenAPI 3 문서(JSON·YAML)를 케이스 초안으로 변환. 저장하지 않으며 상태 없음.
 * <p>
 * 변환 규칙
 * <ul>
 * <li>경로 × 메서드마다 케이스 1건. {@code trace}는 미지원 메서드라 제외하고 경고</li>
 * <li>경로 파라미터 {@code {petId}}는 변수 {@code {{petId}}}로, 필수 query·header 파라미터는 변수로 채움. 선택 파라미터는 생략</li>
 * <li>JSON 요청 본문은 예시({@code example}, {@code examples})가 있을 때만 채움</li>
 * <li>판정은 선언된 2xx 응답 코드({@code 2XX}는 {@code 2xx}). 없으면 {@code 2xx}</li>
 * <li>{@code #/...} 내부 참조만 해석. 외부 파일 참조는 경고 후 생략</li>
 * <li>{@code servers}는 기본 URL을 환경에서 관리하므로 케이스에 넣지 않고 참고용으로만 반환</li>
 * </ul>
 */
public final class OpenApiConverter {

	/** 문서 최대 길이(문자 수). 대형 문서도 수용하되 메모리 사용 제한 */
	public static final int MAX_CONTENT_LENGTH = 5 * 1024 * 1024;

	/** YAML 별칭 확장 공격(billion laughs) 차단 */
	private static final int MAX_YAML_ALIASES = 50;

	/** 참조가 참조를 가리키는 경우의 최대 추적 횟수. 순환 참조 차단 */
	private static final int MAX_REF_DEPTH = 10;

	private static final int MAX_NAME_LENGTH = 200;

	private static final List<String> METHODS = List.of("get", "put", "post", "delete", "options", "head", "patch",
			"trace");

	private static final Pattern PATH_PARAMETER = Pattern.compile("\\{([^{}]+)}");

	private static final Pattern RESPONSE_CODE = Pattern.compile("[1-5][0-9xX]{2}");

	/** 변수 이름에 쓸 수 없는 문자. {@code VariableResolver} 규칙과 동일 */
	private static final Pattern INVALID_VARIABLE_CHARACTER = Pattern.compile("[^A-Za-z0-9_.-]");

	/**
	 * 별칭 수를 제한하여 기하급수적 확장을 막고, 문서 길이 한도까지 읽도록 설정. 로더는 스레드 안전하지 않아 호출마다 생성
	 */
	private final LoadSettings yamlSettings = LoadSettings.builder()
		.setMaxAliasesForCollections(MAX_YAML_ALIASES)
		.setCodePointLimit(MAX_CONTENT_LENGTH)
		.build();

	private final ObjectMapper json = JsonMapper.builder().build();

	/** 예시 본문 서식. 웹 편집기의 {@code JSON.stringify(value, null, 2)}와 같은 모양 */
	private static final DefaultPrettyPrinter BODY_FORMAT = new DefaultPrettyPrinter(Separators.createDefaultInstance()
		.withObjectNameValueSpacing(Separators.Spacing.AFTER)
		.withObjectEmptySeparator("")
		.withArrayEmptySeparator(""))
		.withObjectIndenter(new DefaultIndenter("  ", "\n"))
		.withArrayIndenter(new DefaultIndenter("  ", "\n"));

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
						pathItem, operation, source);
				drafts.add(draft.withName(uniqueName(draft.name(), usedNames)));
			}
		}
		if (drafts.isEmpty()) {
			throw new InvalidRequestException("document defines no supported operations");
		}

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

	private JsonNode parse(String content) {
		if (content == null || content.isBlank()) {
			throw new InvalidRequestException("document must not be empty");
		}
		if (content.length() > MAX_CONTENT_LENGTH) {
			throw new InvalidRequestException("document must be at most " + MAX_CONTENT_LENGTH + " characters");
		}
		Object loaded;
		try {
			// YAML 1.2는 JSON을 포함하므로 두 형식 모두 YAML 파서로 해석. 별칭(*ref)은 펼쳐서 읽음
			loaded = new Load(yamlSettings).loadFromString(content);
		}
		catch (YamlEngineException ex) {
			throw new InvalidRequestException("document is not valid JSON or YAML: " + abbreviate(ex.getMessage()));
		}
		try {
			return json.valueToTree(loaded);
		}
		catch (JacksonException ex) {
			throw new InvalidRequestException("document could not be read: " + abbreviate(ex.getOriginalMessage()));
		}
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
			JsonNode operation, String source) {
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
				case "header" -> headers.put(name, List.of(variable(name, warnings)));
				case "cookie" -> warnings.add("required cookie parameter '" + name + "' is not supported");
				default -> {
					// path 파라미터는 경로 치환에서 처리
				}
			}
		}

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
			if (required) {
				warnings.add("request body has no JSON example; body left empty");
			}
			return null;
		}
		String body = example.isString() ? example.stringValue() : json.writer().with(BODY_FORMAT).writeValueAsString(example);
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

	/**
	 * 파라미터 이름을 변수로. 변수 이름에 쓸 수 없는 문자는 {@code _}로 바꾸고 경고
	 */
	private static String variable(String name, List<String> warnings) {
		String sanitized = INVALID_VARIABLE_CHARACTER.matcher(name.strip()).replaceAll("_");
		if (!sanitized.equals(name)) {
			warnings.add("parameter '" + name + "' is used as variable {{" + sanitized + "}}");
		}
		return "{{" + sanitized + "}}";
	}

	private static String name(JsonNode operation, String source) {
		String name = text(operation.get("summary"));
		if (name == null) {
			name = text(operation.get("operationId"));
		}
		if (name == null) {
			name = source;
		}
		return name.length() <= MAX_NAME_LENGTH ? name : name.substring(0, MAX_NAME_LENGTH);
	}

	/**
	 * 문서 안에서 같은 이름이 반복되면 {@code (2)}, {@code (3)}을 붙임
	 */
	private static String uniqueName(String name, Set<String> used) {
		String candidate = name;
		for (int number = 2; !used.add(candidate); number++) {
			String suffix = " (" + number + ")";
			candidate = name.substring(0, Math.min(name.length(), MAX_NAME_LENGTH - suffix.length())) + suffix;
		}
		return candidate;
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

	private static String text(JsonNode node) {
		if (node == null || node.isNull() || node.isContainer()) {
			return null;
		}
		String value = node.asString().strip();
		return value.isEmpty() ? null : value;
	}

	private static String abbreviate(String message) {
		if (message == null) {
			return "unknown error";
		}
		String line = message.lines().findFirst().orElse(message);
		return line.length() <= 200 ? line : line.substring(0, 200) + "...";
	}

	/**
	 * @param servers 문서의 서버 주소. 환경 기본 URL 설정 참고용
	 * @param warnings 문서 단위 경고 (지원하지 않아 제외한 작업 등)
	 */
	public record ConvertedDocument(String title, String version, List<String> servers, List<CaseDraft> cases,
			List<String> warnings) {
	}

	/**
	 * @param source 원본 작업. 예: {@code GET /pets/{petId}}
	 * @param warnings 변환하면서 생략·대체한 내용
	 */
	public record CaseDraft(String source, String name, Request request, List<Assertion> assertions,
			List<String> warnings) {

		CaseDraft withName(String name) {
			return new CaseDraft(source, name, request, assertions, warnings);
		}

	}

}
