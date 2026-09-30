package com.sentinel.api;

/**
 * 프로젝트 부분 수정. null 필드는 변경하지 않음, description을 빈 문자열로 지정하면 제거.
 *
 * @param webhookUrl 실패 알림을 보낼 Webhook (http(s), 1000자 이하). 빈 문자열이면 삭제하여 서버 기본 채널로
 */
record UpdateProjectRequest(String name, String description, String webhookUrl) {
}
