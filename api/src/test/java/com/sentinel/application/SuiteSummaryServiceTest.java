package com.sentinel.application;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.sentinel.TestcontainersConfiguration;
import com.sentinel.domain.Environment;
import com.sentinel.domain.Project;
import com.sentinel.domain.TestRun;
import com.sentinel.domain.TestSuite;
import com.sentinel.infra.persistence.TestSuiteRepository;
import com.sentinel.runner.HttpMethod;
import com.sentinel.runner.assertion.StatusCodeAssertion;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
import static org.assertj.core.api.Assertions.within;

@SpringBootTest(properties = { "sentinel.schedule.enabled=false",
		"spring.jpa.properties.hibernate.generate_statistics=true" })
@Import({ TestcontainersConfiguration.class, FakeRequestExecutorConfiguration.class, TestFixtures.class,
		SuiteSummaryServiceTest.ClockConfiguration.class })
class SuiteSummaryServiceTest {

	/** 2026-09-10 09:00 KST */
	private static final Instant SEPT_10 = Instant.parse("2026-09-10T00:00:00Z");

	@Autowired
	private SuiteSummaryService suiteSummaries;

	@Autowired
	private TestRunService testRunService;

	@Autowired
	private TestFixtures fixtures;

	@Autowired
	private MutableClock clock;

	@Autowired
	private TestSuiteRepository suites;

	@Autowired
	private EntityManagerFactory entityManagerFactory;

	private Project project;

	private Environment environment;

	@BeforeEach
	void setUp() {
		project = fixtures.project();
		environment = fixtures.environment(project, Map.of());
	}

	@AfterEach
	void cleanUp() {
		fixtures.deleteAll();
	}

	@Test
	void summarizesRunsAndResultsOfThePeriodPerSuite() {
		TestSuite orders = suite("orders", "/ok", "/fail");
		TestSuite idle = suite("idle", "/ok");
		TestSuite archived = suite("archived", "/ok");
		// 기간 전 실행: 판정 수에는 빠지지만 최근 실행이 없으면 최근 실행으로 표시
		runAt(SEPT_10.minus(Duration.ofDays(10)), idle);
		runAt(SEPT_10.minus(Duration.ofDays(1)), orders);
		runAt(SEPT_10, orders);
		archived.archive(SEPT_10);
		suites.save(archived);

		SuiteSummaryService.Summaries summaries = suiteSummaries.summaries(LocalDate.of(2026, 9, 9),
				LocalDate.of(2026, 9, 10));

		assertThat(summaries.suites()).extracting(summary -> summary.suite().name()).containsExactly("idle", "orders");
		SuiteSummaryService.SuiteSummary ordersSummary = summaries.suites().get(1);
		assertThat(ordersSummary.project()).isEqualTo(new IdName(project.getId(), project.getName()));
		assertThat(ordersSummary.runs()).isEqualTo(2);
		// 실행 2회 × 케이스 2건 (통과 1, 실패 1)
		assertThat(ordersSummary.results()).isEqualTo(new TestRunDetail.Summary(4, 2, 2, 0));
		assertThat(ordersSummary.passRate()).isCloseTo(0.5, within(1e-9));
		assertThat(ordersSummary.lastRun().startedAt()).isEqualTo(SEPT_10);
		assertThat(ordersSummary.lastRun().summary()).isEqualTo(new TestRunDetail.Summary(2, 1, 1, 0));

		SuiteSummaryService.SuiteSummary idleSummary = summaries.suites().getFirst();
		assertThat(idleSummary.runs()).isZero();
		assertThat(idleSummary.passRate()).isNull();
		assertThat(idleSummary.lastRun().startedAt()).isEqualTo(SEPT_10.minus(Duration.ofDays(10)));
	}

	@Test
	void listsSuitesThatNeverRan() {
		suite("new", "/ok");

		assertThat(suiteSummaries.summaries(null, null).suites()).singleElement().satisfies(summary -> {
			assertThat(summary.runs()).isZero();
			assertThat(summary.results()).isEqualTo(new TestRunDetail.Summary(0, 0, 0, 0));
			assertThat(summary.lastRun()).isNull();
		});
	}

	@Test
	void usesTheSameNumberOfQueriesForAnyNumberOfSuites() {
		for (int i = 0; i < 4; i++) {
			runAt(SEPT_10, suite("suite-" + i, "/ok"));
		}
		Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		statistics.clear();

		assertThat(suiteSummaries.summaries(null, null).suites()).hasSize(4);

		// 스위트(프로젝트 함께), 실행 수, 판정 수, 최근 실행, 최근 실행의 판정 수
		assertThat(statistics.getPrepareStatementCount()).isEqualTo(5);
	}

	private TestSuite suite(String name, String... paths) {
		TestSuite suite = suites.save(new TestSuite(project, name, null));
		for (String path : paths) {
			fixtures.testCase(suite, HttpMethod.GET, path, null, null,
					path.equals("/fail") ? List.of(new StatusCodeAssertion(List.of("201"))) : List.of());
		}
		return suite;
	}

	private void runAt(Instant startedAt, TestSuite suite) {
		clock.set(startedAt);
		testRunService.runSuite(suite.getId(), environment.getId(), TestRun.Trigger.SCHEDULED, null);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class ClockConfiguration {

		@Bean
		@Primary
		MutableClock mutableClock() {
			return new MutableClock(SEPT_10);
		}

	}

}
