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
import com.sentinel.infra.persistence.TestCaseRepository;
import com.sentinel.runner.HttpMethod;
import com.sentinel.runner.RequestExecutionException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 응답시간 집계. 백분위는 PostgreSQL PERCENTILE_CONT(연속 보간) 기준으로 손으로 계산한 값과 비교
 */
@SpringBootTest(properties = "sentinel.schedule.enabled=false")
@Import({ TestcontainersConfiguration.class, FakeRequestExecutorConfiguration.class, TestFixtures.class,
		ResponseTimeServiceTest.ClockConfiguration.class })
class ResponseTimeServiceTest {

	/** 2026-09-01 09:00 KST */
	private static final Instant SEPT_1_KST_MORNING = Instant.parse("2026-09-01T00:00:00Z");

	private static final LocalDate SEPT_1 = LocalDate.of(2026, 9, 1);

	@Autowired
	private ResponseTimeService responseTimes;

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

	private Project project;

	private TestSuite suite;

	private Environment dev;

	private Environment staging;

	private TestCase orders;

	private long tester;

	@BeforeEach
	void setUp() {
		project = fixtures.project();
		dev = fixtures.environment(project, Map.of());
		staging = fixtures.environment(project, "staging", Map.of());
		suite = fixtures.suite(project);
		orders = fixtures.testCase(suite, HttpMethod.GET, "/orders", null, null);
		tester = fixtures.user().getId();
	}

	@AfterEach
	void cleanUp() {
		fixtures.deleteAll();
		executor.reset();
	}

	@Test
	void aggregatesDailyPercentilesInTheScheduleTimeZone() {
		for (long millis : new long[] { 400, 100, 1000, 300, 200 }) {
			runAt(SEPT_1_KST_MORNING, dev, millis);
		}
		// 다른 환경과 응답을 받지 못한 실행은 제외
		runAt(SEPT_1_KST_MORNING, staging, 9_999);
		failAt(SEPT_1_KST_MORNING, dev);
		// UTC로는 9/1이지만 KST로는 9/2 00:30
		runAt(Instant.parse("2026-09-01T15:30:00Z"), dev, 70);
		runAt(Instant.parse("2026-09-03T01:00:00Z"), dev, 50);

		ResponseTimeService.Trend trend = responseTimes.trend(orders.getId(), dev.getId(), SEPT_1, SEPT_1.plusDays(3));

		assertThat(trend.points()).containsExactly(
				// p95 = 400 + 0.8 × (1000 − 400)
				new ResponseTimeService.DailyPoint(SEPT_1, 5, 300L, 880L, 1000L),
				new ResponseTimeService.DailyPoint(SEPT_1.plusDays(1), 1, 70L, 70L, 70L),
				new ResponseTimeService.DailyPoint(SEPT_1.plusDays(2), 1, 50L, 50L, 50L),
				// 결과 없는 날도 포함
				new ResponseTimeService.DailyPoint(SEPT_1.plusDays(3), 0, null, null, null));
		assertThat(trend.from()).isEqualTo(SEPT_1);
		assertThat(trend.to()).isEqualTo(SEPT_1.plusDays(3));
	}

	@Test
	void defaultsToTheLastThirtyDaysUpToToday() {
		clock.set(Instant.parse("2026-09-30T16:00:00Z"));

		// KST로는 이미 10/1
		ResponseTimeService.Trend trend = responseTimes.trend(orders.getId(), dev.getId(), null, null);

		assertThat(trend.to()).isEqualTo(LocalDate.of(2026, 10, 1));
		assertThat(trend.from()).isEqualTo(LocalDate.of(2026, 9, 2));
		assertThat(trend.points()).hasSize(30);
	}

	@Test
	void summarizesEveryCaseOfTheSuite() {
		TestCase payments = fixtures.testCase(suite, HttpMethod.GET, "/payments", null, null);
		TestCase idle = fixtures.testCase(suite, HttpMethod.GET, "/idle", null, null);
		executor.respondIn(request -> Duration.ofMillis(switch (request.uri().getPath()) {
			case "/orders" -> 100;
			case "/payments" -> 800;
			default -> 5;
		}));
		for (int i = 0; i < 3; i++) {
			clock.set(SEPT_1_KST_MORNING.plus(Duration.ofHours(i)));
			testRunService.runSuite(suite.getId(), dev.getId(), TestRun.Trigger.SCHEDULED, null);
		}
		// idle 케이스는 기간 밖에서만 실행
		executor.respondIn(request -> Duration.ofMillis(1));
		clock.set(SEPT_1_KST_MORNING.minus(Duration.ofDays(10)));
		testRunService.runSuite(suite.getId(), dev.getId(), TestRun.Trigger.SCHEDULED, null);
		idle.archive(clock.instant());
		testCases.save(idle);

		ResponseTimeService.Summary summary = responseTimes.summary(suite.getId(), dev.getId(), SEPT_1, SEPT_1);

		// 보관(삭제)된 케이스는 제외, 등록 순
		assertThat(summary.cases()).containsExactly(
				new ResponseTimeService.CaseStats(orders.getId(), orders.getName(), 3, 100L, 100L, 100L),
				new ResponseTimeService.CaseStats(payments.getId(), payments.getName(), 3, 800L, 800L, 800L));
	}

	@Test
	void listsCasesWithoutResultsAsEmpty() {
		ResponseTimeService.Summary summary = responseTimes.summary(suite.getId(), dev.getId(), SEPT_1, SEPT_1);

		assertThat(summary.cases()).containsExactly(
				new ResponseTimeService.CaseStats(orders.getId(), orders.getName(), 0, null, null, null));
	}

	@Test
	void rejectsForeignEnvironmentsAndInvalidPeriods() {
		Environment foreign = fixtures.environment(fixtures.project(), Map.of());

		assertThatThrownBy(() -> responseTimes.trend(orders.getId(), foreign.getId(), null, null))
			.isInstanceOf(InvalidRequestException.class)
			.hasMessageContaining("does not belong to the project");
		assertThatThrownBy(() -> responseTimes.trend(orders.getId(), dev.getId(), SEPT_1, SEPT_1.minusDays(1)))
			.isInstanceOf(InvalidRequestException.class)
			.hasMessage("from must not be after to");
		assertThatThrownBy(() -> responseTimes.summary(suite.getId(), dev.getId(), SEPT_1, SEPT_1.plusDays(90)))
			.isInstanceOf(InvalidRequestException.class)
			.hasMessage("period must be at most 90 days");
		// 90일까지는 허용
		assertThat(responseTimes.trend(orders.getId(), dev.getId(), SEPT_1, SEPT_1.plusDays(89)).points()).hasSize(90);
		assertThatThrownBy(() -> responseTimes.trend(999_999L, dev.getId(), null, null))
			.isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> responseTimes.summary(suite.getId(), 999_999L, null, null))
			.isInstanceOf(NotFoundException.class);
	}

	private void runAt(Instant startedAt, Environment environment, long millis) {
		clock.set(startedAt);
		executor.respondIn(request -> Duration.ofMillis(millis));
		testRunService.runSuite(suite.getId(), environment.getId(), TestRun.Trigger.MANUAL, tester);
	}

	private void failAt(Instant startedAt, Environment environment) {
		clock.set(startedAt);
		executor.fail(RequestExecutionException.Reason.TIMEOUT);
		testRunService.runSuite(suite.getId(), environment.getId(), TestRun.Trigger.MANUAL, tester);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class ClockConfiguration {

		@Bean
		@Primary
		MutableClock mutableClock() {
			return new MutableClock(SEPT_1_KST_MORNING);
		}

	}

}
