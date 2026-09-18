package com.sentinel.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.sentinel.runner.HttpMethod;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 변수 치환 전 요청 정의. 경로·헤더·본문에 {@code {{변수}}} 사용 가능.
 */
@Embeddable
public class RequestSpec {

	@Enumerated(EnumType.STRING)
	@Column(name = "method", nullable = false, length = 10)
	private HttpMethod method;

	/** Environment baseUrl 기준 상대 경로. {@code /}로 시작, 쿼리스트링 포함 가능 */
	@Column(name = "path", nullable = false, length = 2000)
	private String path;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "headers", nullable = false)
	private Map<String, List<String>> headers;

	@Column(name = "body", columnDefinition = "text")
	private String body;

	protected RequestSpec() {
	}

	public RequestSpec(HttpMethod method, String path, Map<String, List<String>> headers, String body) {
		this.method = Objects.requireNonNull(method, "method");
		this.path = requirePath(path);
		this.headers = copyOf(headers);
		this.body = body;
	}

	private static String requirePath(String path) {
		String value = Preconditions.requireText(path, "path", 2000);
		if (!value.startsWith("/")) {
			throw new IllegalArgumentException("path must start with '/': " + value);
		}
		return value;
	}

	private static Map<String, List<String>> copyOf(Map<String, List<String>> headers) {
		Map<String, List<String>> copy = new LinkedHashMap<>();
		if (headers != null) {
			headers.forEach((name, values) -> copy.put(Preconditions.requireText(name, "header name", 200),
					new ArrayList<>(values)));
		}
		return copy;
	}

	public HttpMethod getMethod() {
		return method;
	}

	public String getPath() {
		return path;
	}

	public Map<String, List<String>> getHeaders() {
		Map<String, List<String>> view = new LinkedHashMap<>();
		headers.forEach((name, values) -> view.put(name, List.copyOf(values)));
		return Collections.unmodifiableMap(view);
	}

	public String getBody() {
		return body;
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof RequestSpec other && method == other.method && path.equals(other.path)
				&& headers.equals(other.headers) && Objects.equals(body, other.body);
	}

	@Override
	public int hashCode() {
		return Objects.hash(method, path, headers, body);
	}

}
