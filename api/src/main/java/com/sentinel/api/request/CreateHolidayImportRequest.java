package com.sentinel.api.request;

import java.util.List;

import com.sentinel.application.HolidayService;

/**
 * @param holidays 등록할 휴일 {@code [{"date": "2026-12-25", "name": "성탄절"}]}. 최대
 * {@value HolidayService#MAX_IMPORT}건
 */
public record CreateHolidayImportRequest(List<HolidayService.NewHoliday> holidays) {
}
