package com.sentinel.runner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class Headers {

	private Headers() {
	}

	/**
	 * 헤더명 대소문자 구분 없는 불변 복사본. 대소문자만 다른 중복 헤더명은 값 병합.
	 */
	static Map<String, List<String>> copyOf(Map<String, List<String>> headers) {
		Map<String, List<String>> merged = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		if (headers != null) {
			headers.forEach((name, values) -> merged.computeIfAbsent(name, key -> new ArrayList<>()).addAll(values));
		}
		Map<String, List<String>> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		merged.forEach((name, values) -> copy.put(name, List.copyOf(values)));
		return Collections.unmodifiableMap(copy);
	}

}
