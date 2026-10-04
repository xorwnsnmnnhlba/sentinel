package com.sentinel.api;

import java.time.LocalDate;

import com.sentinel.application.FailureCauseService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
class FailureCauseController {

	private final FailureCauseService failureCauses;

	/**
	 * 기간 안 스케줄 실행의 실패·오류를 원인별로 (타임아웃·연결 실패·연결 끊김·5xx·4xx·응답 내용 불일치·설정 오류). 기간 생략 시 최근 7일
	 */
	@GetMapping("/api/failure-causes")
	FailureCauseService.FailureCauses list(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) Long suiteId) {
		return failureCauses.list(from, to, suiteId);
	}

}
