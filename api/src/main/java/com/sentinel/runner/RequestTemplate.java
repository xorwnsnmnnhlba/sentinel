package com.sentinel.runner;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 변수 치환 전 요청. 경로·헤더 값·본문에 {@code {{변수}}} 사용 가능, 기본 URL과 헤더명은 치환 대상 아님.
 *
 * @param baseUrl 환경의 기본 URL (끝의 {@code /} 제거된 http(s) 절대 URL)
 * @param path 기본 URL 뒤에 붙는 경로. {@code /} 또는 {@code {{변수}}}로 시작
 */
public record RequestTemplate(HttpMethod method, String baseUrl, String path, Map<String, List<String>> headers,
		String body) {

	public RequestTemplate {
		Objects.requireNonNull(method, "method");
		Objects.requireNonNull(baseUrl, "baseUrl");
		Objects.requireNonNull(path, "path");
		headers = Headers.copyOf(headers);
	}

	/** 경로·헤더 값·본문이 참조하는 변수 이름 (등장 순) */
	public Set<String> variableNames() {
		VariableResolver none = new VariableResolver(Map.of());
		Set<String> names = new LinkedHashSet<>();
		none.resolve(path, names);
		headers.values().forEach(values -> values.forEach(value -> none.resolve(value, names)));
		none.resolve(body, names);
		return Collections.unmodifiableSet(names);
	}

	/**
	 * @throws UnresolvedVariableException 미해결 변수 존재 시. 전체 누락 목록 포함
	 * @throws IllegalArgumentException 치환한 경로가 {@code /}로 시작하지 않거나 결과가 올바른 절대 URI가 아닌 경우
	 */
	public ResolvedRequest resolve(Map<String, String> variables) {
		VariableResolver resolver = new VariableResolver(variables);
		Set<String> missing = new LinkedHashSet<>();

		String resolvedPath = resolver.resolve(path, missing);
		Map<String, List<String>> resolvedHeaders = new LinkedHashMap<>();
		headers.forEach((name, values) -> resolvedHeaders.put(name,
				values.stream().map(value -> resolver.resolve(value, missing)).toList()));
		String resolvedBody = resolver.resolve(body, missing);

		if (!missing.isEmpty()) {
			throw new UnresolvedVariableException(missing);
		}
		// 변수로 시작하는 경로는 치환 후 확인. 미확인 시 "https://gw" + "api/x"처럼 호스트가 바뀐 주소로 전송될 수 있음
		if (!resolvedPath.startsWith("/")) {
			throw new IllegalArgumentException("path must start with '/' after variable substitution: " + resolvedPath);
		}
		return new ResolvedRequest(method, URI.create(baseUrl + resolvedPath), resolvedHeaders, resolvedBody);
	}

}
