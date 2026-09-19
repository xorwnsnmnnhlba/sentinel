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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 변수 치환 전 요청 정의. 경로·헤더·본문에 {@code {{변수}}} 사용 가능.
 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RequestSpec {

	/** 요청 본문 최대 길이(문자). 컬럼 VARCHAR(65536)과 일치 */
	public static final int MAX_BODY_LENGTH = 65_536;

	@Enumerated(EnumType.STRING)
	@Column(name = "http_method", nullable = false, length = 10)
	private HttpMethod method;

	/** Environment baseUrl 기준 상대 경로. {@code /}로 시작, 쿼리스트링 포함 가능 */
	@Column(name = "path", nullable = false, length = 2000)
	private String path;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "headers", nullable = false, columnDefinition = "json")
	private Map<String, List<String>> headers;

	@Column(name = "body", length = MAX_BODY_LENGTH)
	private String body;

	public RequestSpec(HttpMethod method, String path, Map<String, List<String>> headers, String body) {
		if (method == null) {
			throw new IllegalArgumentException("method must not be null");
		}
		this.method = method;
		this.path = requirePath(path);
		this.headers = copyOf(headers);
		this.body = requireBodyLength(body);
	}

	private static String requireBodyLength(String body) {
		if (body != null && body.length() > MAX_BODY_LENGTH) {
			throw new IllegalArgumentException("body must be at most " + MAX_BODY_LENGTH + " characters");
		}
		return body;
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
			headers.forEach((name, values) -> {
				String validName = Preconditions.requireText(name, "header name", 200);
				if (values == null || values.stream().anyMatch(Objects::isNull)) {
					throw new IllegalArgumentException("header '" + validName + "' values must not be null");
				}
				copy.put(validName, new ArrayList<>(values));
			});
		}
		return copy;
	}

	public Map<String, List<String>> getHeaders() {
		Map<String, List<String>> view = new LinkedHashMap<>();
		headers.forEach((name, values) -> view.put(name, List.copyOf(values)));
		return Collections.unmodifiableMap(view);
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
