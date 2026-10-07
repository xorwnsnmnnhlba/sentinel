package com.sentinel.api.request;

import java.util.List;

/**
 * 여러 스케줄 한 번에 변경
 * @param scheduleIds 바꿀 스케줄 (1~200건)
 * @param enabled 활성 여부. 생략 시 유지
 * @param skipHolidays 휴일 건너뛰기. 생략 시 유지
 */
public record UpdateSchedulesRequest(List<Long> scheduleIds, Boolean enabled, Boolean skipHolidays) {
}
