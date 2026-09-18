package com.sentinel.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 실행 이력에 남기는 요청·응답 스냅샷의 자격증명 마스킹. 실제 전송 값은 마스킹 대상 아님.
 */
final class SensitiveHeaders {

	static final String MASK = "****";

	private static final Set<String> NAMES = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

	static {
		NAMES.addAll(List.of("Authorization", "Proxy-Authorization", "Cookie", "Set-Cookie", "X-Api-Key"));
	}

	private SensitiveHeaders() {
	}

	static Map<String, List<String>> mask(Map<String, List<String>> headers) {
		Map<String, List<String>> masked = new LinkedHashMap<>();
		headers.forEach((name, values) -> masked.put(name,
				NAMES.contains(name) ? values.stream().map(value -> MASK).toList() : List.copyOf(values)));
		return masked;
	}

}
