package com.sentinel.application;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.sentinel.TestcontainersConfiguration;
import com.sentinel.domain.Environment;
import com.sentinel.domain.TestCase;
import com.sentinel.domain.TestRun;
import com.sentinel.domain.TestSuite;
import com.sentinel.infra.persistence.EnvironmentRepository;
import com.sentinel.infra.persistence.TestCaseRepository;
import com.sentinel.infra.persistence.TestRunRepository;
import com.sentinel.infra.persistence.TestSuiteRepository;
import com.sentinel.runner.HttpExchange;
import com.sentinel.runner.HttpMethod;
import com.sentinel.runner.extract.Extractor;
import com.sentinel.runner.extract.Extractor.Source;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/** 실패 다시 실행. 실패한 케이스와 그 케이스가 쓰는 추출 값을 만드는 앞 케이스만 실행 */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, FakeRequestExecutorConfiguration.class, TestFixtures.class })
class RunRerunTest {

	@Autowired
	private TestRunService service;

	@Autowired
	private FakeRequestExecutor executor;

	@Autowired
	private TestFixtures fixtures;

	@Autowired
	private TestRunRepository runs;

	@Autowired
	private TestCaseRepository testCases;

	@Autowired
	private TestSuiteRepository suites;

	@Autowired
	private EnvironmentRepository environments;

	private TestSuite suite;

	private Environment environment;

	private long tester;

	/** 이 경로들은 500 (실패), 나머지는 200 */
	private Set<String> failing = Set.of();

	@BeforeEach
	void setUp() {
		suite = fixtures.suite(fixtures.project());
		environment = fixtures.environment(suite.getProject(), Map.of());
		tester = fixtures.user().getId();
		respondByPath();
	}

	@AfterEach
	void cleanUp() {
		fixtures.deleteAll();
		executor.reset();
	}

	@Test
	void rerunsFailedCasesWithTheCasesThatExtractTheValuesTheyUse() {
		TestCase login = caseOf("/login", Map.of(), List.of(new Extractor("token", Source.BODY, "$.token")));
		TestCase orders = caseOf("/orders", Map.of("Authorization", List.of("Bearer {{token}}")), List.of());
		caseOf("/health", Map.of(), List.of());
		TestCase report = caseOf("/report", Map.of(), List.of());
		failing = Set.of("/orders", "/report");
		long original = runSuite();
		clearRequests();
		failing = Set.of("/report");

		TestRunDetail rerun = service.getRun(service.runRerun(original, tester));

		// 실행 순서대로, 통과한 /health는 제외. 로그인에서 추출한 토큰으로 주문 조회
		assertThat(sentPaths()).containsExactly("/login", "/orders", "/report");
		assertThat(executor.requests().get(1).headers()).containsEntry("Authorization", List.of("Bearer t-1"));
		assertThat(rerun.rerunOf()).isEqualTo(original);
		assertThat(rerun.trigger()).isEqualTo(TestRun.Trigger.MANUAL);
		assertThat(rerun.triggeredBy().id()).isEqualTo(tester);
		assertThat(rerun.environmentId()).isEqualTo(environment.getId());
		assertThat(rerun.plannedCount()).isEqualTo(3);
		// 로그인은 원래 실행에서 실패하지 않았지만 값을 만들기 위해 함께 실행
		assertThat(rerun.results()).extracting(TestRunDetail.Result::testCaseId, TestRunDetail.Result::supporting)
			.containsExactly(tuple(login.getId(), true), tuple(orders.getId(), false), tuple(report.getId(), false));
		assertThat(rerun.summary()).isEqualTo(new TestRunDetail.Summary(3, 2, 1, 0));
		// 일부 케이스만 실행한 기록이라 직전 실행과 비교하지 않음
		assertThat(rerun.previousRun()).isNull();
		assertThat(rerun.results()).extracting(TestRunDetail.Result::change).containsOnlyNulls();
	}

	@Test
	void marksNothingAsSupportingOutsideReruns() {
		caseOf("/a", Map.of(), List.of());

		assertThat(service.getRun(runSuite()).results()).extracting(TestRunDetail.Result::supporting)
			.containsExactly(false);
		assertThat(service.getRun(runSuite()).rerunOf()).isNull();
	}

	@Test
	void rerunsTheCasesThatStillFailInARerun() {
		caseOf("/a", Map.of(), List.of());
		caseOf("/b", Map.of(), List.of());
		failing = Set.of("/a", "/b");
		long first = service.runRerun(runSuite(), tester);
		failing = Set.of("/b");
		long second = service.runRerun(first, tester);
		clearRequests();

		service.runRerun(second, tester);

		assertThat(sentPaths()).containsExactly("/b");
	}

	@Test
	void rerunsCasesThatErredAsWell() {
		caseOf("/ok", Map.of(), List.of());
		caseOf("/{{missing}}", Map.of(), List.of());
		long original = runSuite();
		Map<String, String> variables = new LinkedHashMap<>(environment.getVariables());
		variables.put("missing", "now-set");
		environment.replaceVariables(variables);
		environments.save(environment);
		clearRequests();

		TestRunDetail rerun = service.getRun(service.runRerun(original, tester));

		// 원래 실행에서 전송 전 오류(미해결 변수)였던 케이스
		assertThat(sentPaths()).containsExactly("/now-set");
		assertThat(rerun.summary()).isEqualTo(new TestRunDetail.Summary(1, 1, 0, 0));
	}

	@Test
	void leavesRerunsOutOfSuiteComparisonsAndSummaries() {
		caseOf("/a", Map.of(), List.of());
		failing = Set.of("/a");
		long original = runSuite();
		service.runRerun(original, tester);

		// 스위트 요약의 최근 실행은 다시 실행이 아닌 스위트 전체 실행
		assertThat(runs.findLatestPerSuite()).extracting(TestRun::getId).containsExactly(original);
		assertThat(service.getRun(runSuite()).previousRun().id()).isEqualTo(original);
	}

	@Test
	void skipsFailedCasesDeletedSinceTheOriginalRun() {
		TestCase removed = caseOf("/removed", Map.of(), List.of());
		caseOf("/kept", Map.of(), List.of());
		failing = Set.of("/removed", "/kept");
		long original = runSuite();
		removed.archive(Instant.now());
		testCases.save(removed);
		clearRequests();

		service.runRerun(original, tester);

		assertThat(sentPaths()).containsExactly("/kept");
	}

	@Test
	void refusesRunsWithoutFailedCasesStillRunningOrUnknown() {
		TestCase deleted = caseOf("/gone", Map.of(), List.of());
		long passed = runSuite();
		failing = Set.of("/gone");
		long failedThenDeleted = runSuite();
		deleted.archive(Instant.now());
		testCases.save(deleted);
		TestRun running = runs.save(TestRun.start(suite, environment, TestRun.Trigger.MANUAL, Instant.now()));
		long before = runs.count();

		assertThatThrownBy(() -> service.runRerun(passed, tester)).isInstanceOf(ConflictException.class)
			.hasMessage("test run " + passed + " has no failed cases to rerun");
		assertThatThrownBy(() -> service.runRerun(failedThenDeleted, tester)).isInstanceOf(ConflictException.class)
			.hasMessage("test run " + failedThenDeleted + " has no failed cases to rerun");
		assertThatThrownBy(() -> service.runRerun(running.getId(), tester)).isInstanceOf(ConflictException.class)
			.hasMessage("test run " + running.getId() + " is still running");
		assertThatThrownBy(() -> service.runRerun(-1, tester)).isInstanceOf(NotFoundException.class)
			.hasMessage("test run -1 not found");
		assertThat(runs.count()).isEqualTo(before);
	}

	@Test
	void refusesWhenTheEnvironmentOrSuiteWasDeletedSinceTheOriginalRun() {
		caseOf("/a", Map.of(), List.of());
		failing = Set.of("/a");
		long original = runSuite();

		environment.archive(Instant.now());
		environments.save(environment);
		assertThatThrownBy(() -> service.runRerun(original, tester)).isInstanceOf(NotFoundException.class)
			.hasMessage("environment " + environment.getId() + " not found");

		suite.archive(Instant.now());
		suites.save(suite);
		assertThatThrownBy(() -> service.runRerun(original, tester)).isInstanceOf(NotFoundException.class)
			.hasMessage("test suite " + suite.getId() + " not found");
	}

	/** 로그인은 토큰을 담아 응답 */
	private void respondByPath() {
		executor.respondWith(request -> new HttpExchange(request,
				failing.contains(request.uri().getPath()) ? 500 : 200, Map.of(),
				request.uri().getPath().equals("/login") ? "{\"token\": \"t-1\"}" : "{}", Duration.ofMillis(5)));
	}

	private void clearRequests() {
		executor.reset();
		respondByPath();
	}

	private List<String> sentPaths() {
		return executor.requests().stream().map(request -> request.uri().getPath()).toList();
	}

	private TestCase caseOf(String path, Map<String, List<String>> headers, List<Extractor> extractors) {
		return fixtures.testCase(suite, HttpMethod.GET, path, headers, null, List.of(), extractors);
	}

	private long runSuite() {
		return service.runSuite(suite.getId(), environment.getId(), TestRun.Trigger.MANUAL, tester);
	}

}
