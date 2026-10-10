package com.sentinel.api;

import java.time.LocalDate;

import com.sentinel.application.ResponseTimeService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 응답시간 추세. 기간은 {@code sentinel.schedule.zone} 기준 날짜(종료일 포함), 최대 90일
 */
@RestController
@Tag(name = "통계")
@RequiredArgsConstructor
class ResponseTimeController {

	private final ResponseTimeService responseTimes;

	/**
	 * 케이스 1건의 일별 p50·p95·최대. 기간 생략 시 최근 30일
	 */
	@GetMapping("/api/response-time-trends")
	ResponseTimeService.Trend trend(@RequestParam long caseId, @RequestParam long environmentId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		return responseTimes.trend(caseId, environmentId, from, to);
	}

	/**
	 * 케이스·환경별 p95를 바로 앞의 같은 길이 기간과 비교, 가장 나빠진 순. 기간 생략 시 최근 7일
	 */
	@GetMapping("/api/response-time-changes")
	ResponseTimeService.Changes changes(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(defaultValue = "5") int limit) {
		return responseTimes.changes(from, to, limit);
	}

	/**
	 * 스위트의 케이스별 p50·p95·최대. 기간 생략 시 최근 7일
	 */
	@GetMapping("/api/response-time-summaries")
	ResponseTimeService.Summary summary(@RequestParam long suiteId, @RequestParam long environmentId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		return responseTimes.summary(suiteId, environmentId, from, to);
	}

}
