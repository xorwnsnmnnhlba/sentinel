package com.sentinel.api;

import java.util.Map;

/**
 * 환경 부분 수정. null 필드는 변경하지 않음.
 *
 * @param variables 지정 시 전체 교체. 빈 객체면 전부 제거
 */
public record UpdateEnvironmentRequest(String name, String baseUrl, Map<String, String> variables) {
}
