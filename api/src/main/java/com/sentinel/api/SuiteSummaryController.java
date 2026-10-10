package com.sentinel.api;

import java.time.LocalDate;

import com.sentinel.application.SuiteSummaryService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "통계")
@RequiredArgsConstructor
class SuiteSummaryController {

	private final SuiteSummaryService suiteSummaries;

	/**
	 * 스위트별 실행 수·판정 수·통과율·최근 실행. 기간 생략 시 최근 7일
	 */
	@GetMapping("/api/suite-summaries")
	SuiteSummaryService.Summaries list(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		return suiteSummaries.summaries(from, to);
	}

}
