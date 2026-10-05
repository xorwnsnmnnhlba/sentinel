package com.sentinel.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * @param cron 5자리 표준 cron ({@code 분 시 일 월 요일}), 서버 설정 시간대(기본 Asia/Seoul) 기준
 * @param skipHolidays 휴일에는 실행하지 않음. 생략 시 false
 */
public record CreateScheduleRequest(@NotNull Long suiteId, @NotNull Long environmentId, @NotBlank String cron,
		Boolean skipHolidays) {
}
