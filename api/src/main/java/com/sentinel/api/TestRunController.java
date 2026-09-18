package com.sentinel.api;

import java.net.URI;

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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
class TestRunController {

	private final TestRunService testRunService;

	TestRunController(TestRunService testRunService) {
		this.testRunService = testRunService;
	}

	@PostMapping("/test-cases/{testCaseId}/runs")
	ResponseEntity<TestRunDetail> runTestCase(@PathVariable long testCaseId,
			@Valid @RequestBody RunRequest request) {
		long runId = testRunService.runTestCase(testCaseId, request.environmentId());
		return created(runId);
	}

	@PostMapping("/test-suites/{suiteId}/runs")
	ResponseEntity<TestRunDetail> runSuite(@PathVariable long suiteId, @Valid @RequestBody RunRequest request) {
		long runId = testRunService.runSuite(suiteId, request.environmentId(), TestRun.Trigger.MANUAL);
		return created(runId);
	}

	@GetMapping("/test-runs/{runId}")
	TestRunDetail getRun(@PathVariable long runId) {
		return testRunService.getRun(runId);
	}

	private ResponseEntity<TestRunDetail> created(long runId) {
		return ResponseEntity.created(URI.create("/api/test-runs/" + runId)).body(testRunService.getRun(runId));
	}

}
