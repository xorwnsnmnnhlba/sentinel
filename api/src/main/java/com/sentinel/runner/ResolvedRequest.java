package com.sentinel.runner;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 변수 치환이 끝나 그대로 전송 가능한 요청.
 */
public record ResolvedRequest(HttpMethod method, URI uri, Map<String, List<String>> headers, String body) {

	public ResolvedRequest {
		Objects.requireNonNull(method, "method");
		Objects.requireNonNull(uri, "uri");
		if (!uri.isAbsolute()) {
			throw new IllegalArgumentException("uri must be absolute: " + uri);
		}
		headers = Headers.copyOf(headers);
	}

	public static ResolvedRequest of(HttpMethod method, String uri) {
		return new ResolvedRequest(method, URI.create(uri), Map.of(), null);
	}

}
