package com.sentinel.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.sentinel.TestAuthConfiguration;
import com.sentinel.TestcontainersConfiguration;
import com.sentinel.application.FakeRequestExecutorConfiguration;
import com.sentinel.application.TestFixtures;
import com.sentinel.application.TestRunService;
import com.sentinel.application.Transactions;
import com.sentinel.domain.Environment;
import com.sentinel.domain.MonitoringGap;
import com.sentinel.domain.Project;
import com.sentinel.domain.SlowdownAlert;
import com.sentinel.domain.TestCase;
import com.sentinel.domain.TestRun;
import com.sentinel.domain.TestSuite;
import com.sentinel.infra.persistence.EnvironmentRepository;
import com.sentinel.infra.persistence.MonitoringGapRepository;
import com.sentinel.infra.persistence.SlowdownAlertRepository;
import com.sentinel.infra.persistence.TestCaseRepository;
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

	@Autowired
	private SlowdownAlertRepository slowdownAlerts;

	@Autowired
	private TestCaseRepository testCases;

	@Autowired
	private EnvironmentRepository environments;

	@Autowired
	private Transactions transactions;

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
	void listsCasesThatAreStillSlow() {
		Project project = fixtures.project();
		Environment dev = fixtures.environment(project, "dev", Map.of());
		TestSuite payments = fixtures.suite(project);
		TestSuite orders = fixtures.suite(project);
		TestCase pay = fixtures.testCase(payments, HttpMethod.GET, "/pay", null, null);
		TestCase refund = fixtures.testCase(payments, HttpMethod.GET, "/refund", null, null);
		TestCase order = fixtures.testCase(orders, HttpMethod.GET, "/order", null, null);
		TestCase removed = fixtures.testCase(orders, HttpMethod.GET, "/removed", null, null);
		Instant detectedAt = Instant.parse("2026-10-04T11:05:00Z");
		slowdownAlerts.save(new SlowdownAlert(pay.getId(), dev.getId(), 200, 300, detectedAt));
		slowdownAlerts.save(new SlowdownAlert(refund.getId(), dev.getId(), 100, 400, detectedAt));
		slowdownAlerts.save(new SlowdownAlert(order.getId(), dev.getId(), 200, 350, detectedAt));
		SlowdownAlert recovered = new SlowdownAlert(order.getId(), dev.getId(), 200, 900, detectedAt.minusSeconds(86400));
		recovered.resolve(210, detectedAt);
		slowdownAlerts.save(recovered);
		slowdownAlerts.save(new SlowdownAlert(removed.getId(), dev.getId(), 200, 900, detectedAt));
		Environment old = fixtures.environment(project, "old", Map.of());
		slowdownAlerts.save(new SlowdownAlert(pay.getId(), old.getId(), 200, 900, detectedAt));
		transactions.writeWithoutResult(status -> {
			testCases.findById(removed.getId()).orElseThrow().archive(detectedAt.plusSeconds(60));
			environments.findById(old.getId()).orElseThrow().archive(detectedAt.plusSeconds(60));
		});

		// 늘어난 비율이 큰 순 (300% → 75% → 50%). 회복한 알림·삭제된 케이스·환경 제외
		var all = assertThat(mvc.get().uri("/api/slowdowns")).hasStatusOk().bodyJson();
		all.extractingPath("$[*].testCase.name").isEqualTo(List.of(refund.getName(), order.getName(), pay.getName()));
		all.extractingPath("$[0].suite.id").isEqualTo(payments.getId().intValue());
		all.extractingPath("$[0].project.name").isEqualTo(project.getName());
		all.extractingPath("$[0].environment.name").isEqualTo("dev");
		all.extractingPath("$[0].baselineP95").isEqualTo(100);
		all.extractingPath("$[0].detectedP95").isEqualTo(400);
		all.extractingPath("$[0].increase").isEqualTo(3.0);
		all.extractingPath("$[0].detectedAt").isEqualTo("2026-10-04T11:05:00Z");

		assertThat(mvc.get().uri("/api/slowdowns?suiteId=" + orders.getId())).hasStatusOk()
			.bodyJson()
			.extractingPath("$[*].testCase.name")
			.isEqualTo(List.of(order.getName()));
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
