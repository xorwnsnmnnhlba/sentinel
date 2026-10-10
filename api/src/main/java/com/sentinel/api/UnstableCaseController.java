package com.sentinel.api;

import java.time.LocalDate;

import com.sentinel.application.UnstableCaseService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "통계")
@RequiredArgsConstructor
class UnstableCaseController {

	private final UnstableCaseService unstableCases;

	/**
	 * 기간 안 스케줄 실행에서 실패 구간이 2회 이상이거나 재시도한 실행이 2회 이상인 케이스·환경. 기간 생략 시 최근 7일
	 */
	@GetMapping("/api/unstable-cases")
	UnstableCaseService.UnstableCases list(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) Long suiteId) {
		return unstableCases.list(from, to, suiteId);
	}

}
