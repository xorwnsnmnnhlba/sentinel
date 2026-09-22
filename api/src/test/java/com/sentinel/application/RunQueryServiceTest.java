package com.sentinel.application;

import java.util.List;
import java.util.Map;

import com.sentinel.TestcontainersConfiguration;
import com.sentinel.domain.Environment;
import com.sentinel.domain.Project;
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

		PageResponse<RunSummary> page = runQueries.listRuns(null, 0, 20);

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
	void filtersBySuiteAndPaginates() {
		Project project = fixtures.project();
		Environment environment = fixtures.environment(project, Map.of());
		TestSuite target = fixtures.suite(project);
		TestSuite other = fixtures.suite(project);
		for (int i = 0; i < 3; i++) {
			testRunService.runSuite(target.getId(), environment.getId(), TestRun.Trigger.MANUAL, tester());
		}
		testRunService.runSuite(other.getId(), environment.getId(), TestRun.Trigger.MANUAL, tester());

		PageResponse<RunSummary> secondPage = runQueries.listRuns(target.getId(), 1, 2);

		assertThat(secondPage.totalElements()).isEqualTo(3);
		assertThat(secondPage.totalPages()).isEqualTo(2);
		assertThat(secondPage.page()).isEqualTo(1);
		assertThat(secondPage.content()).singleElement()
			.satisfies(run -> assertThat(run.suite().id()).isEqualTo(target.getId()));
	}

	@Test
	void clampsPageAndSize() {
		PageResponse<RunSummary> page = runQueries.listRuns(null, -5, 10_000);

		assertThat(page.page()).isZero();
		assertThat(page.size()).isEqualTo(RunQueryService.MAX_PAGE_SIZE);
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

		PageResponse<RunSummary> singlePage = runQueries.listRuns(null, 0, 20);

		assertThat(singlePage.content()).hasSize(5);
		// 실행+이름(fetch), 판정 집계, 실행 주체. 한 페이지에 모두 들어가 전체 건수 쿼리 생략
		assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);

		statistics.clear();
		PageResponse<RunSummary> firstOfMany = runQueries.listRuns(null, 0, 2);

		assertThat(firstOfMany.totalPages()).isEqualTo(3);
		// 실행+이름(fetch), 전체 건수, 판정 집계, 실행 주체
		assertThat(statistics.getPrepareStatementCount()).isEqualTo(4);
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
