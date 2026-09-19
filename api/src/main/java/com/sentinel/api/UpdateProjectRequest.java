package com.sentinel.api;

/**
 * 프로젝트 부분 수정. null 필드는 변경하지 않음, description을 빈 문자열로 지정하면 제거.
 */
record UpdateProjectRequest(String name, String description) {
}
