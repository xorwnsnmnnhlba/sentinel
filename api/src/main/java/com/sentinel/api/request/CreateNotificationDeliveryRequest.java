package com.sentinel.api.request;

/**
 * 연결 확인 알림
 * @param projectId 그 프로젝트 Webhook으로. 생략 시 서버 기본 채널 (관리자만)
 */
public record CreateNotificationDeliveryRequest(Long projectId) {
}
