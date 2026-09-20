package com.sentinel.domain;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 실행 이력에 남기는 요청·응답 스냅샷에서 시크릿 값 마스킹. 실제 전송 값은 마스킹 대상 아님.
 * <p>
 * 시크릿이 경로·헤더·본문 어디에 치환되었는지 알 수 없으므로 값 일치로 찾아 가림.
 */
public final class SecretValues {

	/** 마스킹 대상 최소 길이. 1~3자 값은 무관한 문자열까지 가릴 수 있어 제외 */
	private static final int MIN_LENGTH = 4;

	private SecretValues() {
	}

	public static String mask(String text, Collection<String> secretValues) {
		if (text == null || secretValues.isEmpty()) {
			return text;
		}
		String masked = text;
		for (String secret : secretValues) {
			if (secret != null && secret.length() >= MIN_LENGTH) {
				masked = masked.replace(secret, SensitiveHeaders.MASK);
			}
		}
		return masked;
	}

	public static Map<String, List<String>> mask(Map<String, List<String>> headers, Collection<String> secretValues) {
		if (secretValues.isEmpty()) {
			return headers;
		}
		return headers.entrySet()
			.stream()
			.collect(Collectors.toMap(Map.Entry::getKey,
					entry -> entry.getValue().stream().map(value -> mask(value, secretValues)).toList(),
					(first, second) -> first, java.util.LinkedHashMap::new));
	}

	public static Set<String> of(Collection<String> values) {
		return Set.copyOf(values);
	}

}
