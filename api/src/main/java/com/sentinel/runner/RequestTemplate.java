package com.sentinel.runner;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 변수 치환 전 요청. URL·헤더 값·본문에 {@code {{변수}}} 사용 가능, 헤더명은 치환 대상 아님.
 */
public record RequestTemplate(HttpMethod method, String url, Map<String, List<String>> headers, String body) {

	public RequestTemplate {
		Objects.requireNonNull(method, "method");
		Objects.requireNonNull(url, "url");
		headers = Headers.copyOf(headers);
	}

	/**
	 * @throws UnresolvedVariableException 미해결 변수 존재 시. 전체 누락 목록 포함
	 * @throws IllegalArgumentException 치환 결과가 올바른 절대 URI가 아닌 경우
	 */
	public ResolvedRequest resolve(Map<String, String> variables) {
		VariableResolver resolver = new VariableResolver(variables);
		Set<String> missing = new LinkedHashSet<>();

		String resolvedUrl = resolver.resolve(url, missing);
		Map<String, List<String>> resolvedHeaders = new LinkedHashMap<>();
		headers.forEach((name, values) -> resolvedHeaders.put(name,
				values.stream().map(value -> resolver.resolve(value, missing)).toList()));
		String resolvedBody = resolver.resolve(body, missing);

		if (!missing.isEmpty()) {
			throw new UnresolvedVariableException(missing);
		}
		return new ResolvedRequest(method, URI.create(resolvedUrl), resolvedHeaders, resolvedBody);
	}

}
