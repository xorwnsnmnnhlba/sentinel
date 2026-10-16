package com.sentinel.api.request;

import java.util.Map;

/**
 * 환경 부분 수정. null 필드는 변경하지 않음.
 *
 * @param variables 지정 시 전체 교체. 빈 객체면 전부 제거
 * @param secrets 이름별 반영. 값이 있으면 등록·교체, null이면 삭제, 목록에 없는 이름은 유지
 * @param version 수정 화면을 열 때 읽은 {@code version}. 지정 시 그사이 다른 곳에서 고쳤으면 409 (생략하면 확인하지 않음)
 */
public record UpdateEnvironmentRequest(String name, String baseUrl, Map<String, String> variables,
		Map<String, String> secrets, Long version) {
}
