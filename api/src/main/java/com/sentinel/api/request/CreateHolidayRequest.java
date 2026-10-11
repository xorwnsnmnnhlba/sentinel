package com.sentinel.api.request;

import java.time.LocalDate;

import jakarta.validation.constraints.NotNull;

/**
 * @param date 휴일 날짜 (YYYY-MM-DD, 서버 설정 시간대 기준)
 * @param name 예: 추석, 임시공휴일 (1~100자)
 */
public record CreateHolidayRequest(@NotNull LocalDate date, String name) {
}
