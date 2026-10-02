package com.sentinel.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.sentinel.TestAuthConfiguration;
import com.sentinel.TestcontainersConfiguration;
import com.sentinel.application.FakeRequestExecutorConfiguration;
import com.sentinel.application.TestFixtures;
import com.sentinel.application.TestRunService;
import com.sentinel.domain.Environment;
import com.sentinel.domain.MonitoringGap;
import com.sentinel.domain.Project;
import com.sentinel.domain.TestRun;
import com.sentinel.domain.TestSuite;
import com.sentinel.infra.persistence.MonitoringGapRepository;
import com.sentinel.runner.HttpMethod;
import com.sentinel.runner.assertion.StatusCodeAssertion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 대시보드가 쓰는 API의 요청·응답 형태. 집계 값은 서비스 테스트에서 검증
 */
@SpringBootTest(properties = "sentinel.schedule.enabled=false")
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, FakeRequestExecutorConfiguration.class, TestFixtures.class,
		TestAuthConfiguration.class })
class DashboardApiTest {

	@Autowired
	private MockMvcTester mvc;

	@Autowired
	private TestFixtures fixtures;

	@Autowired
	private TestRunService testRunService;

	@Autowired
	private MonitoringGapRepository monitoringGaps;

	@AfterEach
	void cleanUp() {
		fixtures.deleteAll();
	}

	private void gap(String startedAt, String endedAt, int missedSchedules) {
		monitoringGaps.save(new MonitoringGap(Instant.parse(startedAt), Instant.parse(endedAt), missedSchedules));
	}

	@Test
	void listsUnstableCasesOfThePeriod() {
		var body = assertThat(mvc.get().uri("/api/unstable-cases?from=2026-09-01&to=2026-09-07")).hasStatusOk()
			.bodyJson();
		body.extractingPath("$.from").isEqualTo("2026-09-01");
		body.extractingPath("$.to").isEqualTo("2026-09-07");
		body.extractingPath("$.cases").asArray().isEmpty();
		assertThat(mvc.get().uri("/api/unstable-cases?from=2026-09-07&to=2026-09-01"))
			.hasStatus(HttpStatus.BAD_REQUEST);
	}

	@Test
	void listsMonitoringGapsOverlappingThePeriod() {
		// 기간 2026-09-01 ~ 09-07 (KST) = 2026-08-31T15:00Z 이상 2026-09-07T15:00Z 미만
		gap("2026-08-31T14:00:00Z", "2026-08-31T15:00:00Z", 1);
		gap("2026-09-03T01:00:00Z", "2026-09-03T03:30:00Z", 4);
		gap("2026-09-07T14:00:00Z", "2026-09-07T16:00:00Z", 2);
		gap("2026-08-31T13:00:00Z", "2026-08-31T14:59:59Z", 1);
		gap("2026-09-07T15:00:00Z", "2026-09-07T16:00:00Z", 1);

		var body = assertThat(mvc.get().uri("/api/monitoring-gaps?from=2026-09-01&to=2026-09-07")).hasStatusOk()
			.bodyJson();

		body.extractingPath("$.from").isEqualTo("2026-09-01");
		body.extractingPath("$.to").isEqualTo("2026-09-07");
		// 최근 시작 순, 기간 경계에 걸친 공백 포함
		body.extractingPath("$.gaps[*].startedAt")
			.isEqualTo(List.of("2026-09-07T14:00:00Z", "2026-09-03T01:00:00Z", "2026-08-31T14:00:00Z"));
		body.extractingPath("$.gaps[1].endedAt").isEqualTo("2026-09-03T03:30:00Z");
		body.extractingPath("$.gaps[1].missedSchedules").isEqualTo(4);
		assertThat(mvc.get().uri("/api/monitoring-gaps?from=2026-09-07&to=2026-09-01"))
			.hasStatus(HttpStatus.BAD_REQUEST);
	}

	@Test
	void returnsSuiteSummariesWithPassRateAndLastRun() {
		Project project = fixtures.project();
		Environment environment = fixtures.environment(project, Map.of());
		TestSuite suite = fixtures.suite(project);
		fixtures.testCase(suite, HttpMethod.GET, "/ok", null, null);
		fixtures.testCase(suite, HttpMethod.GET, "/fail", null, null, List.of(new StatusCodeAssertion(List.of("201"))));
		long run = testRunService.runSuite(suite.getId(), environment.getId(), TestRun.Trigger.SCHEDULED, null);

		var body = assertThat(mvc.get().uri("/api/suite-summaries")).hasStatusOk().bodyJson();

		body.extractingPath("$.suites[0].suite.id").isEqualTo(suite.getId().intValue());
		body.extractingPath("$.suites[0].project.name").isEqualTo(project.getName());
		body.extractingPath("$.suites[0].runs").isEqualTo(1);
		body.extractingPath("$.suites[0].failingRuns").isEqualTo(1);
		body.extractingPath("$.suites[0].results.failed").isEqualTo(1);
		body.extractingPath("$.suites[0].passRate").isEqualTo(0.5);
		body.extractingPath("$.suites[0].lastRun.id").isEqualTo((int) run);
		body.extractingPath("$.suites[0].lastRun.status").isEqualTo("COMPLETED");

		// 실패가 있는 실행만
		assertThat(mvc.get().uri("/api/runs?failing=true")).hasStatusOk()
			.bodyJson()
			.extractingPath("$.content[*].id")
			.isEqualTo(List.of((int) run));
	}

	@Test
	void validatesDashboardParameters() {
		assertThat(mvc.get().uri("/api/response-time-changes")).hasStatusOk()
			.bodyJson()
			.extractingPath("$.changes")
			.isEqualTo(List.of());
		assertThat(mvc.get().uri("/api/response-time-changes?limit=0")).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(mvc.get().uri("/api/suite-summaries?from=2026-01-01&to=2026-12-31"))
			.hasStatus(HttpStatus.BAD_REQUEST);
	}

}
