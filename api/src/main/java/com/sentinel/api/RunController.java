package com.sentinel.api;

import java.net.URI;

import com.sentinel.application.PageResponse;
import com.sentinel.application.RunQueryService;
import com.sentinel.application.RunSummary;
import com.sentinel.application.TestRunDetail;
import com.sentinel.application.TestRunService;
import com.sentinel.domain.TestRun;
import jakarta.validation.Valid;
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
class RunController {

	private final TestRunService testRunService;

	private final RunQueryService runQueryService;

	RunController(TestRunService testRunService, RunQueryService runQueryService) {
		this.testRunService = testRunService;
		this.runQueryService = runQueryService;
	}

	/**
	 * 최신순 실행 목록. size는 1~100으로 보정
	 */
	@GetMapping
	PageResponse<RunSummary> list(@RequestParam(required = false) Long suiteId,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		return runQueryService.listRuns(suiteId, page, size);
	}

	@GetMapping("/{runId}")
	TestRunDetail get(@PathVariable long runId) {
		return testRunService.getRun(runId);
	}

	/**
	 * 수동 실행. 실행 완료 후 결과 포함 응답
	 */
	@PostMapping
	ResponseEntity<TestRunDetail> create(@Valid @RequestBody CreateRunRequest request) {
		long runId = request.suiteId() != null
				? testRunService.runSuite(request.suiteId(), request.environmentId(), TestRun.Trigger.MANUAL)
				: testRunService.runTestCase(request.caseId(), request.environmentId());
		return ResponseEntity.created(URI.create("/api/runs/" + runId)).body(testRunService.getRun(runId));
	}

}
