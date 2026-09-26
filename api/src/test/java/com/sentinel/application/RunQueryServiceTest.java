package com.sentinel.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import com.sentinel.TestcontainersConfiguration;
import com.sentinel.domain.Environment;
import com.sentinel.domain.Project;
import com.sentinel.domain.TestCase;
import com.sentinel.domain.TestRun;
import com.sentinel.domain.TestSuite;
import com.sentinel.runner.HttpMethod;
import com.sentinel.runner.assertion.StatusCodeAssertion;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Import({ TestcontainersConfiguration.class, FakeRequestExecutorConfiguration.class, TestFixtures.class })
class RunQueryServiceTest {

	@Autowired
	private RunQueryService runQueries;

	@Autowired
	private TestRunService testRunService;

	@Autowired
	private FakeRequestExecutor executor;

	@Autowired
	private TestFixtures fixtures;

	@Autowired
	private EntityManagerFactory entityManagerFactory;

	@AfterEach
	void cleanUp() {
		fixtures.deleteAll();
		testerId = null;
		executor.reset();
	}

	@Test
	void listsRunsNewestFirstWithNamesAndVerdictCounts() {
		Project project = fixtures.project();
		Environment environment = fixtures.environment(project, Map.of());
		TestSuite billing = fixtures.suite(project);
		fixtures.testCase(billing, HttpMethod.GET, "/ok", null, null);
		fixtures.testCase(billing, HttpMethod.GET, "/fail", null, null, List.of(new StatusCodeAssertion(List.of("201"))));
		fixtures.testCase(billing, HttpMethod.GET, "/{{missing}}", null, null);
		TestSuite empty = fixtures.suite(project);

		long first = testRunService.runSuite(billing.getId(), environment.getId(), TestRun.Trigger.SCHEDULED, tester());
		long second = testRunService.runSuite(empty.getId(), environment.getId(), TestRun.Trigger.MANUAL, tester());

		PageResponse<RunSummary> page = runQueries.listRuns(RunFilter.none(), 0, 20);

		assertThat(page.totalElements()).isEqualTo(2);
		assertThat(page.content()).extracting(RunSummary::id).containsExactly(second, first);
		RunSummary billingRun = page.content().get(1);
		assertThat(billingRun.project()).isEqualTo(new IdName(project.getId(), project.getName()));
		assertThat(billingRun.suite()).isEqualTo(new IdName(billing.getId(), billing.getName()));
		assertThat(billingRun.environment()).isEqualTo(new IdName(environment.getId(), "dev"));
		assertThat(billingRun.trigger()).isEqualTo(TestRun.Trigger.SCHEDULED);
		assertThat(billingRun.summary()).isEqualTo(new TestRunDetail.Summary(3, 1, 1, 1));
		assertThat(page.content().getFirst().summary()).isEqualTo(new TestRunDetail.Summary(0, 0, 0, 0));
	}

	@Test
	void marksSingleCaseRunsWithTheirCase() {
		Project project = fixtures.project();
		Environment environment = fixtures.environment(project, Map.of());
		TestSuite billing = fixtures.suite(project);
		TestCase target = fixtures.testCase(billing, HttpMethod.GET, "/ok", null, null);
		fixtures.testCase(billing, HttpMethod.GET, "/other", null, null);

		long whole = testRunService.runSuite(billing.getId(), environment.getId(), TestRun.Trigger.MANUAL, tester());
		long single = testRunService.runTestCase(target.getId(), environment.getId(), tester());

		List<RunSummary> content = runQueries.listRuns(RunFilter.none(), 0, 20).content();

		assertThat(content).extracting(RunSummary::id).containsExactly(single, whole);
		assertThat(content.getFirst().testCase()).isEqualTo(new IdName(target.getId(), target.getName()));
		assertThat(content.getFirst().summary()).isEqualTo(new TestRunDetail.Summary(1, 1, 0, 0));
		assertThat(content.get(1).testCase()).isNull();
		// 스위트 조건으로 좁혀도 단건 실행 포함 (실행 이력은 모든 실행)
		assertThat(ids(suiteFilter(billing.getId()))).containsExactly(single, whole);
	}

	@Test
	void filtersBySuiteAndPaginates() {
		Project project = fixtures.project();
		Environment environment = fixtures.environment(project, Map.of());
		TestSuite target = fixtures.suite(project);
		TestSuite other = fixtures.suite(project);
		for (int i = 0; i < 3; i++) {
			testRunService.runSuite(target.getId(), environment.getId(), TestRun.Trigger.MANUAL, tester());
		}
		testRunService.runSuite(other.getId(), environment.getId(), TestRun.Trigger.MANUAL, tester());

		PageResponse<RunSummary> secondPage = runQueries.listRuns(suiteFilter(target.getId()), 1, 2);

		assertThat(secondPage.totalElements()).isEqualTo(3);
		assertThat(secondPage.totalPages()).isEqualTo(2);
		assertThat(secondPage.page()).isEqualTo(1);
		assertThat(secondPage.content()).singleElement()
			.satisfies(run -> assertThat(run.suite().id()).isEqualTo(target.getId()));
	}

	@Test
	void filtersRunsWithFailuresOrErrors() {
		Project project = fixtures.project();
		Environment environment = fixtures.environment(project, Map.of());
		TestSuite passing = fixtures.suite(project);
		fixtures.testCase(passing, HttpMethod.GET, "/ok", null, null);
		TestSuite failing = fixtures.suite(project);
		fixtures.testCase(failing, HttpMethod.GET, "/ok", null, null);
		fixtures.testCase(failing, HttpMethod.GET, "/fail", null, null, List.of(new StatusCodeAssertion(List.of("201"))));
		TestSuite unresolved = fixtures.suite(project);
		fixtures.testCase(unresolved, HttpMethod.GET, "/{{missing}}", null, null);

		testRunService.runSuite(passing.getId(), environment.getId(), TestRun.Trigger.MANUAL, tester());
		long failed = testRunService.runSuite(failing.getId(), environment.getId(), TestRun.Trigger.MANUAL, tester());
		long errored = testRunService.runSuite(unresolved.getId(), environment.getId(), TestRun.Trigger.MANUAL,
				tester());

		PageResponse<RunSummary> page = runQueries.listRuns(
				new RunFilter(null, null, null, null, null, null, true), 0, 20);

		// 실패 1건과 통과 1건이 섞인 실행도 한 번만 (결과 행 수만큼 중복되지 않음)
		assertThat(page.content()).extracting(RunSummary::id).containsExactly(errored, failed);
		assertThat(page.totalElements()).isEqualTo(2);
	}

	@Test
	void listsRunsThatNobodyStartedByHand() {
		// 스케줄 실행만 있는 페이지. 실행 주체가 하나도 없어도 목록 조회 가능
		Project project = fixtures.project();
		Environment environment = fixtures.environment(project, Map.of());
		TestSuite suite = fixtures.suite(project);
		long scheduled = testRunService.runSuite(suite.getId(), environment.getId(), TestRun.Trigger.SCHEDULED, null);

		PageResponse<RunSummary> page = runQueries.listRuns(RunFilter.none(), 0, 20);

		assertThat(page.content()).singleElement().satisfies(run -> {
			assertThat(run.id()).isEqualTo(scheduled);
			assertThat(run.triggeredBy()).isNull();
		});
	}

	@Test
	void clampsPageAndSize() {
		PageResponse<RunSummary> page = runQueries.listRuns(RunFilter.none(), -5, 10_000);

		assertThat(page.page()).isZero();
		assertThat(page.size()).isEqualTo(RunQueryService.MAX_PAGE_SIZE);
	}

	@Test
	void filtersByProjectEnvironmentAndStatus() {
		Project project = fixtures.project();
		Environment dev = fixtures.environment(project, Map.of());
		Environment staging = fixtures.environment(project, "staging", Map.of());
		TestSuite suite = fixtures.suite(project);
		fixtures.testCase(suite, HttpMethod.GET, "/a", null, null);
		Project otherProject = fixtures.project();
		TestSuite otherSuite = fixtures.suite(otherProject);
		fixtures.testCase(otherSuite, HttpMethod.GET, "/a", null, null);
		Environment otherEnvironment = fixtures.environment(otherProject, Map.of());

		long devRun = testRunService.runSuite(suite.getId(), dev.getId(), TestRun.Trigger.MANUAL, tester());
		long stagingRun = testRunService.runSuite(suite.getId(), staging.getId(), TestRun.Trigger.MANUAL, tester());
		long otherRun = testRunService.runSuite(otherSuite.getId(), otherEnvironment.getId(), TestRun.Trigger.MANUAL,
				tester());

		assertThat(ids(new RunFilter(project.getId(), null, null, null, null, null, false)))
			.containsExactly(stagingRun, devRun);
		assertThat(ids(new RunFilter(null, null, dev.getId(), null, null, null, false))).containsExactly(devRun);
		assertThat(ids(new RunFilter(null, otherSuite.getId(), null, null, null, null, false))).containsExactly(otherRun);
		assertThat(ids(new RunFilter(null, null, null, TestRun.Status.COMPLETED, null, null, false)))
			.containsExactly(otherRun, stagingRun, devRun);
		assertThat(ids(new RunFilter(null, null, null, TestRun.Status.RUNNING, null, null, false))).isEmpty();
		// 조건을 함께 지정하면 모두 만족하는 실행만
		assertThat(ids(new RunFilter(project.getId(), null, staging.getId(), null, null, null, false)))
			.containsExactly(stagingRun);
		assertThat(ids(new RunFilter(otherProject.getId(), null, dev.getId(), null, null, null, false))).isEmpty();
	}

	@Test
	void filtersByDateInTheScheduleTimeZone() {
		Project project = fixtures.project();
		Environment environment = fixtures.environment(project, Map.of());
		TestSuite suite = fixtures.suite(project);
		fixtures.testCase(suite, HttpMethod.GET, "/a", null, null);
		long run = testRunService.runSuite(suite.getId(), environment.getId(), TestRun.Trigger.MANUAL, tester());
		LocalDate today = LocalDate.ofInstant(Instant.now(), ZoneId.of("Asia/Seoul"));

		assertThat(ids(new RunFilter(null, null, null, null, today, today, false))).contains(run);
		assertThat(ids(new RunFilter(null, null, null, null, today.plusDays(1), null, false))).doesNotContain(run);
		assertThat(ids(new RunFilter(null, null, null, null, null, today.minusDays(1), false))).doesNotContain(run);
	}

	private List<Long> ids(RunFilter filter) {
		return runQueries.listRuns(filter, 0, 50).content().stream().map(RunSummary::id).toList();
	}

	@Test
	void usesFixedNumberOfQueriesRegardlessOfRunCount() {
		// 실행 주체가 여러 명이어도 한 번에 조회
		Project project = fixtures.project();
		Environment environment = fixtures.environment(project, Map.of());
		for (int i = 0; i < 5; i++) {
			TestSuite suite = fixtures.suite(project);
			fixtures.testCase(suite, HttpMethod.GET, "/a", null, null);
			testRunService.runSuite(suite.getId(), environment.getId(), TestRun.Trigger.MANUAL, tester());
		}
		Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		statistics.clear();

		PageResponse<RunSummary> singlePage = runQueries.listRuns(RunFilter.none(), 0, 20);

		assertThat(singlePage.content()).hasSize(5);
		// 실행+이름(fetch), 판정 집계, 실행 주체. 한 페이지에 모두 들어가 전체 건수 쿼리 생략
		assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);

		statistics.clear();
		PageResponse<RunSummary> firstOfMany = runQueries.listRuns(RunFilter.none(), 0, 2);

		assertThat(firstOfMany.totalPages()).isEqualTo(3);
		// 실행+이름(fetch), 전체 건수, 판정 집계, 실행 주체
		assertThat(statistics.getPrepareStatementCount()).isEqualTo(4);
	}

	@Test
	void looksUpTargetCasesOfSingleCaseRunsInOneQuery() {
		Project project = fixtures.project();
		Environment environment = fixtures.environment(project, Map.of());
		TestSuite suite = fixtures.suite(project);
		for (int i = 0; i < 4; i++) {
			TestCase testCase = fixtures.testCase(suite, HttpMethod.GET, "/case-" + i, null, null);
			testRunService.runTestCase(testCase.getId(), environment.getId(), tester());
		}
		Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		statistics.clear();

		assertThat(runQueries.listRuns(RunFilter.none(), 0, 20).content()).allSatisfy(
				run -> assertThat(run.testCase()).isNotNull());
		// 실행+이름(fetch), 판정 집계, 실행 주체, 대상 케이스
		assertThat(statistics.getPrepareStatementCount()).isEqualTo(4);
	}


	private static RunFilter suiteFilter(long suiteId) {
		return new RunFilter(null, suiteId, null, null, null, null, false);
	}

	/** 수동 실행 주체. 실행마다 같은 계정을 사용하도록 한 번만 생성 */
	private long tester() {
		if (testerId == null) {
			testerId = fixtures.user().getId();
		}
		return testerId;
	}

	private Long testerId;

}
