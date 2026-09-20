package com.sentinel.api;

import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * @param baseUrl http(s) 절대 URL. 끝의 {@code /}는 제거하여 저장
 * @param variables 경로·헤더·본문의 {@code {{변수}}} 값. 평문 저장이므로 토큰·API 키는 secrets 사용
 * @param secrets 토큰·API 키. 암호화 저장하며 조회 시 이름만 노출
 */
record CreateEnvironmentRequest(@NotNull Long projectId, @NotBlank String name, @NotBlank String baseUrl,
		Map<String, String> variables, Map<String, String> secrets) {
}
