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
import static org.assertj.core.api.Assertions.within;

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

	@Test
	void ranksCasesByHowMuchSlowerTheyGot() {
		TestCase payments = fixtures.testCase(suite, HttpMethod.GET, "/payments", null, null);
		TestCase rare = fixtures.testCase(suite, HttpMethod.GET, "/rare", null, null);
		// 이전 기간 9/1~9/7: orders 100ms, payments 400ms, rare 50ms
		// 현재 기간 9/8~9/14: orders 150ms(+50%), payments 300ms(-25%), rare는 2건뿐이라 제외
		for (int day = 0; day < 3; day++) {
			runSuiteAt(SEPT_1_KST_MORNING.plus(Duration.ofDays(day)), Map.of("/orders", 100, "/payments", 400, "/rare", 50));
			runSuiteAt(SEPT_1_KST_MORNING.plus(Duration.ofDays(7 + day)),
					day < 2 ? Map.of("/orders", 150, "/payments", 300, "/rare", 900) : Map.of("/orders", 150, "/payments", 300));
		}
		// 다른 환경은 따로 비교 (결과가 한 기간에만 있어 제외)
		runAt(SEPT_1_KST_MORNING.plus(Duration.ofDays(8)), staging, 5_000);

		ResponseTimeService.Changes changes = responseTimes.changes(SEPT_1.plusDays(7), SEPT_1.plusDays(13), 5);

		assertThat(changes.previousFrom()).isEqualTo(SEPT_1);
		assertThat(changes.previousTo()).isEqualTo(SEPT_1.plusDays(6));
		assertThat(changes.changes()).extracting(change -> change.testCase().id()).containsExactly(orders.getId(),
				payments.getId());
		ResponseTimeService.Change slower = changes.changes().getFirst();
		assertThat(slower.previousP95()).isEqualTo(100);
		assertThat(slower.currentP95()).isEqualTo(150);
		assertThat(slower.change()).isCloseTo(0.5, within(1e-9));
		assertThat(slower.environment().name()).isEqualTo("dev");
		assertThat(slower.suite().id()).isEqualTo(suite.getId());
		assertThat(changes.changes().get(1).change()).isCloseTo(-0.25, within(1e-9));
		// 한도, 보관된 케이스 제외
		assertThat(responseTimes.changes(SEPT_1.plusDays(7), SEPT_1.plusDays(13), 1).changes()).hasSize(1);
		orders.archive(clock.instant());
		testCases.save(orders);
		assertThat(responseTimes.changes(SEPT_1.plusDays(7), SEPT_1.plusDays(13), 5).changes())
			.extracting(change -> change.testCase().id())
			.containsExactly(payments.getId());
		assertThat(rare.getId()).isNotNull();
	}

	@Test
	void rejectsAnInvalidLimit() {
		assertThatThrownBy(() -> responseTimes.changes(null, null, 0)).isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> responseTimes.changes(null, null, 51)).isInstanceOf(InvalidRequestException.class)
			.hasMessage("limit must be between 1 and 50");
	}

	private void runSuiteAt(Instant startedAt, Map<String, Integer> millisByPath) {
		clock.set(startedAt);
		// 지정하지 않은 경로는 응답 실패로 처리하여 집계에서 제외
		executor.respondIn(request -> {
			Integer millis = millisByPath.get(request.uri().getPath());
			if (millis == null) {
				throw new RequestExecutionException(RequestExecutionException.Reason.TIMEOUT, request, Duration.ofMillis(1), null);
			}
			return Duration.ofMillis(millis);
		});
		testRunService.runSuite(suite.getId(), dev.getId(), TestRun.Trigger.SCHEDULED, null);
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
