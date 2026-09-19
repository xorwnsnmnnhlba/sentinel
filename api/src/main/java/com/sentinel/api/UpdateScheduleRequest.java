package com.sentinel.api;

/**
 * 스케줄 부분 수정. null 필드는 변경하지 않음.
 */
public record UpdateScheduleRequest(Boolean enabled) {
}
