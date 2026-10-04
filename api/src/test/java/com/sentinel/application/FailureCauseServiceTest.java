package com.sentinel.application;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.sentinel.TestcontainersConfiguration;
import com.sentinel.domain.Environment;
import com.sentinel.domain.Project;
import com.sentinel.domain.TestCase;
import com.sentinel.domain.TestRun;
import com.sentinel.domain.TestSuite;
import com.sentinel.infra.persistence.EnvironmentRepository;
import com.sentinel.infra.persistence.TestCaseRepository;
import com.sentinel.runner.HttpExchange;
import com.sentinel.runner.HttpMethod;
import com.sentinel.runner.RequestExecutionException;
import com.sentinel.runner.assertion.StatusCodeAssertion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실패 원인 분류. 경로마다 응답·전송 실패를 정해 두고 스케줄 실행을 만들어 원인별 집계 확인
 */
@SpringBootTest(properties = { "sentinel.schedule.enabled=false", "sentinel.runner.retry.delay=0s" })
@Import({ TestcontainersConfiguration.class, FakeRequestExecutorConfiguration.class, TestFixtures.class,
		FailureCauseServiceTest.ClockConfiguration.class })
class FailureCauseServiceTest {

	/** 2026-08-12 09:00 KST */
	private static final Instant AUG_12 = Instant.parse("2026-08-12T00:00:00Z");

	private static final LocalDate FROM = LocalDate.of(2026, 8, 10);

	private static final LocalDate TO = LocalDate.of(2026, 8, 16);

	@Autowired
	private FailureCauseService failureCauses;

	@Autowired
	private TestRunService testRunService;

	@Autowired
	private FakeRequestExecutor executor;

	@Autowired
	private TestFixtures fixtures;

	@Autowired
	private MutableClock clock;

	@Autowired
	private TestCaseRepository testCases;

	@Autowired
	private EnvironmentRepository environments;

	@Autowired
	private Transactions transactions;

	private TestSuite suite;

	private Environment dev;

	@BeforeEach
	void setUp() {
		Project project = fixtures.project();
		dev = fixtures.environment(project, "dev", Map.of());
		suite = fixtures.suite(project);
		executor.respondWith(request -> switch (request.uri().getPath()) {
			case "/timeout" -> throw failure(RequestExecutionException.Reason.TIMEOUT, request);
			case "/refused" -> throw failure(RequestExecutionException.Reason.CONNECTION_FAILED, request);
			case "/reset" -> throw failure(RequestExecutionException.Reason.IO_ERROR, request);
			// 경계값: 500부터 서버 오류, 400부터 요청 거부, 399는 응답 내용 불일치
			case "/down" -> new HttpExchange(request, 500, Map.of(), "", Duration.ofMillis(5));
			case "/forbidden" -> new HttpExchange(request, 400, Map.of(), "", Duration.ofMillis(5));
			case "/redirect" -> new HttpExchange(request, 399, Map.of(), "", Duration.ofMillis(5));
			default -> new HttpExchange(request, 200, Map.of(), "{}", Duration.ofMillis(5));
		});
	}

	@AfterEach
	void cleanUp() {
		fixtures.deleteAll();
		executor.reset();
	}

	@Test
	void groupsFailuresByCauseFromScheduledRunsOnly() {
		fixtures.testCase(suite, HttpMethod.GET, "/ok", null, null);
		fixtures.testCase(suite, HttpMethod.GET, "/timeout", null, null);
		fixtures.testCase(suite, HttpMethod.GET, "/refused", null, null);
		fixtures.testCase(suite, HttpMethod.GET, "/reset", null, null);
		fixtures.testCase(suite, HttpMethod.GET, "/down", null, null);
		fixtures.testCase(suite, HttpMethod.GET, "/forbidden", null, null);
		TestCase mismatch = fixtures.testCase(suite, HttpMethod.GET, "/mismatch", null, null,
				List.of(new StatusCodeAssertion(List.of("201"))));
		fixtures.testCase(suite, HttpMethod.GET, "/redirect", null, null);
		fixtures.testCase(suite, HttpMethod.GET, "/{{missing}}", null, null);
		// 스케줄 실행 2회는 집계, 수동 실행·기간 밖 실행은 제외
		run(AUG_12, TestRun.Trigger.SCHEDULED);
		run(AUG_12.plus(Duration.ofHours(1)), TestRun.Trigger.SCHEDULED);
		run(AUG_12.plus(Duration.ofHours(2)), TestRun.Trigger.MANUAL);
		run(AUG_12.minus(Duration.ofDays(10)), TestRun.Trigger.SCHEDULED);

		FailureCauseService.FailureCauses result = failureCauses.list(FROM, TO, null);

		assertThat(result.from()).isEqualTo(FROM);
		assertThat(result.to()).isEqualTo(TO);
		assertThat(result.results()).isEqualTo(16);
		// 결과가 많은 순, 같으면 원인 순서대로
		assertThat(result.causes()).extracting(FailureCauseService.CauseSummary::cause)
			.containsExactly(FailureCauseService.Cause.ASSERTION_MISMATCH, FailureCauseService.Cause.TIMEOUT,
					FailureCauseService.Cause.CONNECTION_FAILED, FailureCauseService.Cause.CONNECTION_LOST,
					FailureCauseService.Cause.SERVER_ERROR, FailureCauseService.Cause.CLIENT_ERROR,
					FailureCauseService.Cause.CONFIGURATION);
		assertThat(result.causes()).filteredOn(cause -> cause.cause() != FailureCauseService.Cause.ASSERTION_MISMATCH)
			.allSatisfy(cause -> {
				assertThat(cause.results()).isEqualTo(2);
				assertThat(cause.caseCount()).isEqualTo(1);
			});
		assertThat(result.causes()).extracting(FailureCauseService.CauseSummary::group)
			.containsExactly(FailureCauseService.Group.API, FailureCauseService.Group.NETWORK,
					FailureCauseService.Group.NETWORK, FailureCauseService.Group.NETWORK, FailureCauseService.Group.API,
					FailureCauseService.Group.API, FailureCauseService.Group.SETUP);
		// 판정 규칙 불일치(200)와 399 응답
		FailureCauseService.CauseSummary assertion = result.causes().getFirst();
		assertThat(assertion.results()).isEqualTo(4);
		assertThat(assertion.caseCount()).isEqualTo(2);
		assertThat(assertion.cases()).filteredOn(count -> count.testCase().name().equals(mismatch.getName()))
			.singleElement()
			.satisfies(count -> {
				assertThat(count.suite().id()).isEqualTo(suite.getId());
				assertThat(count.environment().name()).isEqualTo("dev");
				assertThat(count.results()).isEqualTo(2);
			});
	}

	@Test
	void ordersByResultsAndKeepsTheTopCasesPerCause() {
		Environment staging = fixtures.environment(suite.getProject(), "staging", Map.of());
		TestCase down1 = fixtures.testCase(suite, HttpMethod.GET, "/down", null, null);
		fixtures.testCase(suite, HttpMethod.POST, "/down", null, null);
		fixtures.testCase(suite, HttpMethod.PUT, "/down", null, null);
		fixtures.testCase(suite, HttpMethod.DELETE, "/down", null, null);
		fixtures.testCase(suite, HttpMethod.GET, "/forbidden", null, null);
		run(AUG_12, TestRun.Trigger.SCHEDULED);
		run(AUG_12.plus(Duration.ofHours(1)), TestRun.Trigger.SCHEDULED);
		clock.set(AUG_12.plus(Duration.ofHours(2)));
		testRunService.runSuite(suite.getId(), staging.getId(), TestRun.Trigger.SCHEDULED, null);

		FailureCauseService.FailureCauses result = failureCauses.list(FROM, TO, null);

		// 5xx 4건 × 3회, 4xx 1건 × 3회
		assertThat(result.causes()).extracting(FailureCauseService.CauseSummary::cause)
			.containsExactly(FailureCauseService.Cause.SERVER_ERROR, FailureCauseService.Cause.CLIENT_ERROR);
		FailureCauseService.CauseSummary server = result.causes().getFirst();
		assertThat(server.results()).isEqualTo(12);
		// 케이스·환경 쌍 8개 중 결과가 많은 3개 (dev 2회씩)
		assertThat(server.caseCount()).isEqualTo(8);
		assertThat(server.cases()).hasSize(FailureCauseService.TOP_CASES)
			.allSatisfy(count -> assertThat(count.results()).isEqualTo(2));
		assertThat(server.cases()).extracting(count -> count.environment().name()).containsOnly("dev");

		// 삭제된 케이스·환경 제외 (케이스 1건 × 3회, staging 실행 4건)
		transactions.writeWithoutResult(
				status -> testCases.findById(down1.getId()).orElseThrow().archive(AUG_12.plus(Duration.ofDays(1))));
		assertThat(failureCauses.list(FROM, TO, null).causes().getFirst().results()).isEqualTo(9);
		transactions.writeWithoutResult(
				status -> environments.findById(staging.getId()).orElseThrow().archive(AUG_12.plus(Duration.ofDays(1))));
		assertThat(failureCauses.list(FROM, TO, null).causes().getFirst().results()).isEqualTo(6);
	}

	@Test
	void filtersBySuite() {
		fixtures.testCase(suite, HttpMethod.GET, "/down", null, null);
		TestSuite other = fixtures.suite(suite.getProject());
		fixtures.testCase(other, HttpMethod.GET, "/forbidden", null, null);
		run(AUG_12, TestRun.Trigger.SCHEDULED);
		clock.set(AUG_12);
		testRunService.runSuite(other.getId(), dev.getId(), TestRun.Trigger.SCHEDULED, null);

		assertThat(failureCauses.list(FROM, TO, other.getId()).causes()).singleElement()
			.extracting(FailureCauseService.CauseSummary::cause)
			.isEqualTo(FailureCauseService.Cause.CLIENT_ERROR);
		assertThat(failureCauses.list(FROM, TO, null).results()).isEqualTo(2);
	}

	private void run(Instant at, TestRun.Trigger trigger) {
		clock.set(at);
		testRunService.runSuite(suite.getId(), dev.getId(), trigger,
				trigger == TestRun.Trigger.MANUAL ? fixtures.user().getId() : null);
	}

	private static RequestExecutionException failure(RequestExecutionException.Reason reason,
			com.sentinel.runner.ResolvedRequest request) {
		return new RequestExecutionException(reason, request, Duration.ofMillis(1), null);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class ClockConfiguration {

		@Bean
		@Primary
		MutableClock mutableClock() {
			return new MutableClock(AUG_12);
		}

	}

}
