package com.sentinel.application.importing;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sentinel.application.CatalogQueryService.Request;
import com.sentinel.application.InvalidRequestException;
import com.sentinel.domain.RequestSpec;
import com.sentinel.runner.HttpMethod;
import com.sentinel.runner.assertion.StatusCodeAssertion;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import static com.sentinel.application.importing.ImportDocuments.format;
import static com.sentinel.application.importing.ImportDocuments.parse;
import static com.sentinel.application.importing.ImportDocuments.text;
import static com.sentinel.application.importing.ImportDocuments.truncateName;
import static com.sentinel.application.importing.ImportDocuments.uniqueName;

/**
 * Postman Collection v2.0·v2.1(JSON)을 케이스 초안으로 변환. 저장하지 않으며 상태 없음.
 * <p>
 * 변환 규칙
 * <ul>
 * <li>요청마다 케이스 1건. 폴더는 이름 앞에 {@code 폴더 / 하위 폴더 / 요청}으로 표시</li>
 * <li>URL의 호스트(보통 {@code {{baseUrl}}})는 환경 기본 URL이 대신하므로 빼고 경로·query만 사용. 호스트는 참고용으로 반환</li>
 * <li>경로 변수 {@code :id}는 컬렉션에 적힌 값, 없으면 변수 {@code {{id}}}</li>
 * <li>{@code {{변수}}} 문법이 Sentinel과 같아 헤더·본문의 변수는 그대로 유지</li>
 * <li>인증은 요청 → 상위 폴더 → 컬렉션 순으로 상속</li>
 * <li>자격증명(인증 값, Authorization 등 민감 헤더)이 변수가 아닌 값이면 케이스에 평문으로 남기지 않도록 변수로 바꾸고 안내</li>
 * <li>판정은 저장된 예시 응답의 2xx 코드, 없으면 {@code 2xx}. 스크립트(pre-request·test)는 미지원 경고</li>
 * </ul>
 */
public final class PostmanConverter {

	private static final Pattern SUPPORTED_SCHEMA = Pattern.compile("collection/v2\\.[01]\\.");

	/** 원문 URL의 스킴·호스트 부분과 나머지 */
	private static final Pattern RAW_URL = Pattern.compile("^((?:[A-Za-z][A-Za-z0-9+.-]*://)?[^/?#]*)([^#]*)");

	private static final Pattern VARIABLE = Pattern.compile("\\{\\{[^{}]+}}");

	private static final Pattern AUTHORIZATION_VALUE = Pattern.compile("^(Bearer|Basic|Token|Bot)\\s+(.+)$",
			Pattern.CASE_INSENSITIVE);

	/** 값이 평문이면 변수로 바꾸는 헤더와 변수 이름. 실행 이력 마스킹 대상({@code SensitiveHeaders})과 같은 목록 */
	private static final Map<String, String> CREDENTIAL_HEADERS = new TreeMap<>(
			String.CASE_INSENSITIVE_ORDER);

	static {
		CREDENTIAL_HEADERS.putAll(Map.of("Authorization", "authorization", "Proxy-Authorization",
				"proxyAuthorization", "Cookie", "cookie", "X-Api-Key", "apiKey"));
	}

	private final JsonNodeFactory nodes = JsonNodeFactory.instance;

	/**
	 * @throws InvalidRequestException 해석할 수 없는 문서, Postman Collection v2가 아닌 문서, 요청이 없는 컬렉션
	 */
	public ConvertedDocument convert(String content) {
		JsonNode root = parse(content);
		requireCollectionV2(root);

		Conversion conversion = new Conversion(root);
		conversion.walk(root.path("item"), List.of(), root.get("auth"));
		if (conversion.drafts.isEmpty()) {
			throw new InvalidRequestException("collection defines no supported requests");
		}

		List<String> warnings = new ArrayList<>(conversion.warnings);
		warnings.addAll(conversion.notes.values());
		Set<String> variables = new TreeSet<>();
		root.path("variable").forEach(variable -> {
			String key = text(variable.get("key"));
			if (key != null) {
				variables.add(key);
			}
		});
		if (!variables.isEmpty()) {
			// 값은 자격증명일 수 있어 이름만 안내
			warnings.add("collection variables are not imported; define them in the environment: "
					+ String.join(", ", variables));
		}
		JsonNode info = root.path("info");
		return new ConvertedDocument(text(info.get("name")), version(info.get("version")),
				List.copyOf(conversion.servers), conversion.drafts, warnings);
	}

	private static void requireCollectionV2(JsonNode root) {
		if (root == null || !root.isObject()) {
			throw new InvalidRequestException("document must be a JSON or YAML object");
		}
		if (root.has("_postman_variable_scope") || (root.has("values") && !root.has("item"))) {
			throw new InvalidRequestException("this is a Postman environment, not a collection");
		}
		String schema = text(root.path("info").get("schema"));
		if (schema == null) {
			if (root.has("requests")) {
				throw new InvalidRequestException(
						"only Postman Collection v2.0 and v2.1 are supported (found v1); export the collection again");
			}
			throw new InvalidRequestException("document is not a Postman collection (info.schema is missing)");
		}
		if (!SUPPORTED_SCHEMA.matcher(schema).find()) {
			throw new InvalidRequestException(
					"only Postman Collection v2.0 and v2.1 are supported (found schema " + schema + ")");
		}
	}

	/** v2.0은 버전을 {@code {major, minor, patch}} 객체로 표기 */
	private static String version(JsonNode version) {
		if (version != null && version.isObject()) {
			return version.path("major").asString("0") + "." + version.path("minor").asString("0") + "."
					+ version.path("patch").asString("0");
		}
		return text(version);
	}

	/** 한 컬렉션을 변환하는 동안의 누적 상태 */
	private final class Conversion {

		private final JsonNode root;

		private final List<CaseDraft> drafts = new ArrayList<>();

		private final List<String> warnings = new ArrayList<>();

		/** 등록할 시크릿·변수 안내. 요청마다 반복하지 않도록 변수 이름별 한 번 */
		private final Map<String, String> notes = new LinkedHashMap<>();

		private final Set<String> servers = new LinkedHashSet<>();

		private final Set<String> usedNames = new HashSet<>();

		private Conversion(JsonNode root) {
			this.root = root;
		}

		/**
		 * @param inheritedAuth 상위 폴더·컬렉션의 인증. 없으면 null
		 */
		private void walk(JsonNode items, List<String> folders, JsonNode inheritedAuth) {
			for (JsonNode item : items) {
				String name = text(item.get("name"));
				if (item.has("item")) {
					List<String> path = new ArrayList<>(folders);
					path.add(name == null ? "(folder)" : name);
					walk(item.get("item"), path, item.has("auth") ? item.get("auth") : inheritedAuth);
				}
				else if (item.has("request")) {
					convertRequest(item, folders, name, inheritedAuth);
				}
			}
		}

		private void convertRequest(JsonNode item, List<String> folders, String itemName, JsonNode inheritedAuth) {
			JsonNode request = item.get("request");
			// 요청을 URL 문자열만으로 적은 경우 GET
			JsonNode url = request.isString() ? request : request.get("url");
			String methodName = request.isString() ? "GET"
					: String.valueOf(text(request.get("method"))).toUpperCase(Locale.ROOT);
			if ("NULL".equals(methodName)) {
				methodName = "GET";
			}
			String source = methodName + " " + displayUrl(url);
			HttpMethod method;
			try {
				method = HttpMethod.valueOf(methodName);
			}
			catch (IllegalArgumentException ex) {
				warnings.add(source + ": method " + methodName + " is not supported and was skipped");
				return;
			}

			List<String> caseWarnings = new ArrayList<>();
			scriptWarnings(item, caseWarnings);
			Map<String, List<String>> headers = new LinkedHashMap<>();
			StringBuilder query = new StringBuilder();
			String path = path(url, query);
			if (!request.isString()) {
				headers(request.get("header"), headers);
			}
			String body = request.isString() ? null : body(request.get("body"), headers, caseWarnings);
			JsonNode auth = request.isObject() && request.has("auth") ? request.get("auth") : inheritedAuth;
			applyAuth(auth, headers, query, caseWarnings);
			protectCredentialHeaders(headers, caseWarnings);

			List<String> names = new ArrayList<>(folders);
			names.add(itemName == null ? source : itemName);
			String name = uniqueName(truncateName(String.join(" / ", names)), usedNames);
			Request converted = new Request(method, path + query, headers, body);
			drafts.add(new CaseDraft(source, name, converted,
					List.of(new StatusCodeAssertion(successCodes(item.path("response")))), List.copyOf(caseWarnings)));
		}

		/**
		 * 경로 부분. 호스트는 빼고 참고용 서버 목록에 기록. query는 {@code query}에 추가
		 */
		private String path(JsonNode url, StringBuilder query) {
			List<String> segments = new ArrayList<>();
			Map<String, String> pathValues = new LinkedHashMap<>();
			if (url != null && url.isObject() && url.has("path")) {
				recordServer(url);
				JsonNode path = url.get("path");
				if (path.isArray()) {
					path.forEach(segment -> segments.add(segment.isObject() ? segment.path("value").asString("")
							: segment.asString("")));
				}
				else {
					segments.addAll(List.of(path.asString("").split("/", -1)));
				}
				url.path("variable").forEach(variable -> {
					String key = text(variable.get("key"));
					if (key != null) {
						pathValues.put(key, text(variable.get("value")));
					}
				});
				url.path("query").forEach(parameter -> {
					String key = text(parameter.get("key"));
					if (key != null && !parameter.path("disabled").asBoolean(false)) {
						appendQuery(query, key, text(parameter.get("value")));
					}
				});
			}
			else {
				String raw = url == null ? "" : url.isObject() ? String.valueOf(text(url.get("raw"))) : url.asString("");
				Matcher matcher = RAW_URL.matcher(raw.strip());
				String host = "";
				String rest = raw.strip();
				if (matcher.find()) {
					host = matcher.group(1);
					rest = matcher.group(2);
				}
				if (!host.isEmpty()) {
					servers.add(resolveHost(host));
				}
				int queryStart = rest.indexOf('?');
				if (queryStart >= 0) {
					String rawQuery = rest.substring(queryStart + 1);
					if (!rawQuery.isEmpty()) {
						query.append(query.isEmpty() ? '?' : '&').append(rawQuery);
					}
					rest = rest.substring(0, queryStart);
				}
				segments.addAll(List.of(rest.split("/", -1)));
			}
			StringBuilder path = new StringBuilder();
			for (String segment : segments) {
				if (segment.isEmpty() && path.isEmpty()) {
					continue;
				}
				path.append('/').append(pathSegment(segment, pathValues));
			}
			return path.isEmpty() ? "/" : path.toString();
		}

		/** 경로 변수 {@code :id}는 컬렉션에 적힌 값, 없으면 변수 */
		private String pathSegment(String segment, Map<String, String> pathValues) {
			if (!segment.startsWith(":") || segment.length() == 1) {
				return segment;
			}
			String key = segment.substring(1);
			String value = pathValues.get(key);
			return value != null ? value : ImportDocuments.variable(key, new ArrayList<>());
		}

		private void recordServer(JsonNode url) {
			JsonNode host = url.get("host");
			String hostText = host == null ? null
					: host.isArray() ? String.join(".", host.valueStream().map(node -> node.asString("")).toList())
							: host.asString("");
			if (hostText == null || hostText.isEmpty()) {
				return;
			}
			String protocol = text(url.get("protocol"));
			String port = text(url.get("port"));
			servers.add(resolveHost((protocol == null ? "" : protocol + "://") + hostText + (port == null ? ""
					: ":" + port)));
		}

		/**
		 * 호스트가 컬렉션 변수 하나({@code {{baseUrl}}})이고 주소 값이 있으면 그 값으로 안내. 주소가 아닌 값은 노출하지 않음
		 */
		private String resolveHost(String host) {
			Matcher matcher = VARIABLE.matcher(host);
			if (!matcher.matches()) {
				return host;
			}
			String key = host.substring(2, host.length() - 2).strip();
			for (JsonNode variable : root.path("variable")) {
				String value = text(variable.get("value"));
				if (key.equals(text(variable.get("key"))) && value != null
						&& (value.startsWith("http://") || value.startsWith("https://"))) {
					return host + " (" + value + ")";
				}
			}
			return host;
		}

		/** 헤더 목록. v2.1은 {@code {key, value, disabled}} 배열, 문자열({@code Key: Value} 줄 단위)도 허용 */
		private void headers(JsonNode header, Map<String, List<String>> headers) {
			if (header == null) {
				return;
			}
			if (header.isString()) {
				header.asString().lines().forEach(line -> {
					int colon = line.indexOf(':');
					if (colon > 0) {
						headers.put(line.substring(0, colon).strip(), List.of(line.substring(colon + 1).strip()));
					}
				});
				return;
			}
			header.forEach(entry -> {
				String key = text(entry.get("key"));
				if (key != null && !entry.path("disabled").asBoolean(false)) {
					headers.put(key, List.of(entry.path("value").asString("")));
				}
			});
		}

		private String body(JsonNode body, Map<String, List<String>> headers, List<String> caseWarnings) {
			if (body == null || !body.isObject() || body.path("disabled").asBoolean(false)) {
				return null;
			}
			String mode = String.valueOf(text(body.get("mode")));
			String converted = switch (mode) {
				case "raw" -> {
					String raw = body.path("raw").asString("");
					String language = String.valueOf(text(body.path("options").path("raw").get("language")));
					String contentType = switch (language) {
						case "json" -> "application/json";
						case "xml" -> "application/xml";
						case "text" -> "text/plain";
						default -> null;
					};
					if (contentType != null && !raw.isEmpty() && !hasHeader(headers, "Content-Type")) {
						headers.put("Content-Type", List.of(contentType));
					}
					yield raw.isEmpty() ? null : raw;
				}
				case "urlencoded" -> {
					List<String> pairs = new ArrayList<>();
					body.path("urlencoded").forEach(parameter -> {
						String key = text(parameter.get("key"));
						if (key != null && !parameter.path("disabled").asBoolean(false)) {
							pairs.add(encode(key) + "=" + encode(parameter.path("value").asString("")));
						}
					});
					if (!pairs.isEmpty() && !hasHeader(headers, "Content-Type")) {
						headers.put("Content-Type", List.of("application/x-www-form-urlencoded"));
					}
					yield pairs.isEmpty() ? null : String.join("&", pairs);
				}
				case "graphql" -> {
					ObjectNode graphql = nodes.objectNode();
					graphql.put("query", body.path("graphql").path("query").asString(""));
					String variables = text(body.path("graphql").get("variables"));
					if (variables != null) {
						try {
							graphql.set("variables", parse(variables));
						}
						catch (InvalidRequestException ex) {
							caseWarnings.add("GraphQL variables are not valid JSON and were left out");
						}
					}
					if (!hasHeader(headers, "Content-Type")) {
						headers.put("Content-Type", List.of("application/json"));
					}
					yield format(graphql);
				}
				default -> {
					caseWarnings.add("request body mode '" + mode + "' is not supported; body left empty");
					yield null;
				}
			};
			if (converted != null && converted.length() > RequestSpec.MAX_BODY_LENGTH) {
				caseWarnings.add("request body exceeds " + RequestSpec.MAX_BODY_LENGTH + " characters; body left empty");
				return null;
			}
			return converted;
		}

		/**
		 * 인증을 헤더·query로. 평문 자격증명은 변수로 바꿈 (Basic은 아이디·비밀번호 인코딩이 필요해 항상 변수)
		 */
		private void applyAuth(JsonNode auth, Map<String, List<String>> headers, StringBuilder query,
				List<String> caseWarnings) {
			if (auth == null || !auth.isObject()) {
				return;
			}
			String type = String.valueOf(text(auth.get("type")));
			switch (type) {
				case "noauth", "null" -> {
				}
				case "bearer" -> headers.put("Authorization",
						List.of("Bearer " + credential(authParameter(auth, "bearer", "token"), "bearerToken",
								"bearer token", caseWarnings)));
				case "oauth2" -> headers.put("Authorization",
						List.of("Bearer " + credential(authParameter(auth, "oauth2", "accessToken"), "accessToken",
								"OAuth 2.0 access token", caseWarnings)));
				case "basic" -> {
					headers.put("Authorization", List.of("Basic {{basicAuth}}"));
					notes.putIfAbsent("basicAuth", "basic auth is sent as 'Authorization: Basic {{basicAuth}}'; add "
							+ "{{basicAuth}} as a secret holding base64 of 'username:password'");
				}
				case "apikey" -> {
					String key = authParameter(auth, "apikey", "key");
					if (key == null) {
						caseWarnings.add("API key auth has no key name and was left out");
						return;
					}
					String value = credential(authParameter(auth, "apikey", "value"), "apiKey", "API key",
							caseWarnings);
					if ("query".equals(authParameter(auth, "apikey", "in"))) {
						appendQuery(query, key, value);
					}
					else {
						headers.put(key, List.of(value));
					}
				}
				default -> caseWarnings.add("auth type '" + type + "' is not supported");
			}
		}

		/**
		 * 인증 값. 변수가 포함된 값은 그대로, 평문이면 변수로 바꾸고 안내 (값 자체는 어디에도 남기지 않음)
		 */
		private String credential(String value, String variable, String what, List<String> caseWarnings) {
			if (value != null && VARIABLE.matcher(value).find()) {
				return value;
			}
			if (value != null) {
				caseWarnings.add("literal " + what + " was replaced with {{" + variable + "}}");
			}
			notes.putIfAbsent(variable, "add {{" + variable + "}} as a secret of the environment (" + what + ")");
			return "{{" + variable + "}}";
		}

		/** 직접 적은 Authorization·Cookie 등 헤더도 평문이면 변수로 */
		private void protectCredentialHeaders(Map<String, List<String>> headers, List<String> caseWarnings) {
			headers.replaceAll((name, values) -> {
				String variable = CREDENTIAL_HEADERS.get(name);
				if (variable == null) {
					return values;
				}
				return values.stream().map(value -> {
					if (VARIABLE.matcher(value).find()) {
						return value;
					}
					Matcher scheme = AUTHORIZATION_VALUE.matcher(value);
					String prefix = name.toLowerCase(Locale.ROOT).endsWith("authorization") && scheme.matches()
							? scheme.group(1) + " " : "";
					return prefix + credential(value, variable, "'" + name + "' header value", caseWarnings);
				}).toList();
			});
		}

		private void scriptWarnings(JsonNode item, List<String> caseWarnings) {
			for (JsonNode event : item.path("event")) {
				JsonNode exec = event.path("script").path("exec");
				boolean hasCode = exec.isString() ? !exec.asString().isBlank()
						: exec.valueStream().anyMatch(line -> !line.asString("").isBlank());
				if (hasCode) {
					String listen = String.valueOf(text(event.get("listen")));
					caseWarnings.add((listen.equals("prerequest") ? "pre-request" : listen)
							+ " script is not supported; add assertions instead");
				}
			}
		}

		/** 저장된 예시 응답의 2xx 코드. 없으면 2xx */
		private List<String> successCodes(JsonNode responses) {
			Set<String> codes = new LinkedHashSet<>();
			responses.forEach(response -> {
				int code = response.path("code").asInt(0);
				if (code >= 200 && code < 300) {
					codes.add(String.valueOf(code));
				}
			});
			return codes.isEmpty() ? List.of("2xx") : List.copyOf(codes);
		}

	}

	/**
	 * 인증 설정 값. v2.1은 {@code [{key, value}]} 배열, v2.0은 {@code {key: value}} 객체
	 */
	private static String authParameter(JsonNode auth, String type, String key) {
		JsonNode parameters = auth.get(type);
		if (parameters == null) {
			return null;
		}
		if (parameters.isObject()) {
			return text(parameters.get(key));
		}
		for (JsonNode parameter : parameters) {
			if (key.equals(text(parameter.get("key")))) {
				return text(parameter.get("value"));
			}
		}
		return null;
	}

	private static void appendQuery(StringBuilder query, String key, String value) {
		query.append(query.isEmpty() ? '?' : '&').append(key);
		if (value != null) {
			query.append('=').append(value);
		}
	}

	private static boolean hasHeader(Map<String, List<String>> headers, String name) {
		return headers.keySet().stream().anyMatch(key -> key.equalsIgnoreCase(name));
	}

	/** 폼 값 인코딩. {@code {{변수}}}는 실행 시 치환되도록 그대로 둠 */
	private static String encode(String value) {
		Matcher matcher = VARIABLE.matcher(value);
		StringBuilder encoded = new StringBuilder();
		int last = 0;
		while (matcher.find()) {
			encoded.append(URLEncoder.encode(value.substring(last, matcher.start()), StandardCharsets.UTF_8))
				.append(matcher.group());
			last = matcher.end();
		}
		return encoded.append(URLEncoder.encode(value.substring(last), StandardCharsets.UTF_8)).toString();
	}

	/** 후보 식별용 원본 URL. Postman에 보이는 형태 그대로 */
	private static String displayUrl(JsonNode url) {
		if (url == null) {
			return "";
		}
		if (url.isString()) {
			return url.asString().strip();
		}
		String raw = text(url.get("raw"));
		return raw == null ? "" : raw;
	}

}
