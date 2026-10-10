package com.sentinel.api;

import java.time.LocalDate;

import com.sentinel.application.MonitoringGapService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "통계")
@RequiredArgsConstructor
class MonitoringGapController {

	private final MonitoringGapService monitoringGaps;

	/**
	 * 기간과 겹치는 모니터링 공백 (스케줄이 예정 시각보다 크게 늦게 실행된 기간). 기간 생략 시 최근 7일
	 */
	@GetMapping("/api/monitoring-gaps")
	MonitoringGapService.MonitoringGaps list(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		return monitoringGaps.list(from, to);
	}

}
