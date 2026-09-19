package com.sentinel.api;

import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * @param baseUrl http(s) 절대 URL. 끝의 {@code /}는 제거하여 저장
 * @param variables 경로·헤더·본문의 {@code {{변수}}} 값. 평문 저장이므로 토큰·API 키 입력 금지
 */
public record CreateEnvironmentRequest(@NotNull Long projectId, @NotBlank String name, @NotBlank String baseUrl,
		Map<String, String> variables) {
}
