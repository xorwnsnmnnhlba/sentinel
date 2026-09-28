package com.sentinel.api;

import java.net.URI;
import java.time.LocalDate;

import com.sentinel.application.PageResponse;
import com.sentinel.application.RunFilter;
import com.sentinel.application.RunQueryService;
import com.sentinel.application.RunSummary;
import com.sentinel.application.TestRunDetail;
import com.sentinel.application.TestRunService;
import com.sentinel.domain.TestRun;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/runs")
@RequiredArgsConstructor
class RunController {

	private final TestRunService testRunService;

	private final RunQueryService runQueryService;

	private final CurrentUser currentUser;

	/**
	 * 최신순 실행 목록. size는 1~100으로 보정, 지정하지 않은 필터는 조건에서 제외
	 *
	 * @param from 시작일(포함). 기간은 스케줄 시간대(기본 Asia/Seoul) 기준
	 * @param to 종료일(포함)
	 */
	@GetMapping
	PageResponse<RunSummary> list(@RequestParam(required = false) Long projectId,
			@RequestParam(required = false) Long suiteId, @RequestParam(required = false) Long environmentId,
			@RequestParam(required = false) TestRun.Status status,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(defaultValue = "false") boolean failing, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return runQueryService.listRuns(new RunFilter(projectId, suiteId, environmentId, status, from, to, failing),
				page, size);
	}

	@GetMapping("/{runId}")
	TestRunDetail get(@PathVariable long runId) {
		return testRunService.getRun(runId);
	}

	/**
	 * 수동 실행 시작. 실행을 만들고 바로 202 + Location으로 응답 (본문은 그 시점의 실행, 보통 RUNNING).
	 * 케이스 실행은 백그라운드에서 진행하므로 완료 여부는 {@code GET /api/runs/{id}}의 status로 확인.
	 * 동시 수동 실행 한도에 도달하면 429
	 */
	@PostMapping
	ResponseEntity<TestRunDetail> create(@Valid @RequestBody CreateRunRequest request) {
		long triggeredBy = currentUser.get().id();
		long runId;
		if (request.suiteId() != null) {
			runId = testRunService.startSuite(request.suiteId(), request.environmentId(), triggeredBy);
		}
		else if (request.caseId() != null) {
			runId = testRunService.startTestCase(request.caseId(), request.environmentId(), triggeredBy);
		}
		else {
			runId = testRunService.startThroughCase(request.throughCaseId(), request.environmentId(), triggeredBy);
		}
		return ResponseEntity.accepted()
			.location(URI.create("/api/runs/" + runId))
			.body(testRunService.getRun(runId));
	}

}
