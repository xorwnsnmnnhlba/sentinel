package com.sentinel.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.sentinel.TestcontainersConfiguration;
import com.sentinel.domain.Environment;
import com.sentinel.domain.Project;
import com.sentinel.domain.TestResult;
import com.sentinel.domain.TestRun;
import com.sentinel.domain.TestSuite;
import com.sentinel.infra.persistence.TestRunRepository;
import com.sentinel.runner.HttpMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 보관기간이 지난 실행의 요청·응답 원문만 비우고 실행·판정 기록은 유지. {@link RawResultRetention#purgeExpired()}를 직접 호출하여 시각을 제어
 */
@SpringBootTest(properties = { "sentinel.schedule.enabled=false", "sentinel.retention.raw-retention=30d",
		"sentinel.retention.batch-size=2" })
@Import({ TestcontainersConfiguration.class, FakeRequestExecutorConfiguration.class, TestFixtures.class,
		RawResultRetentionTest.ClockConfiguration.class })
class RawResultRetentionTest {

	private static final Instant START = Instant.parse("2026-08-01T00:00:00Z");

	@Autowired
	private RawResultRetention retention;

	@Autowired
	private TestRunService testRunService;

	@Autowired
	private TestRunRepository runs;

	@Autowired
	private FakeRequestExecutor executor;

	@Autowired
	private TestFixtures fixtures;

	@Autowired
	private MutableClock clock;

	@Autowired
	private List<ScheduledTaskHolder> taskHolders;

	private TestSuite suite;

	private Environment environment;

	private long tester;

	@BeforeEach
	void setUp() {
		clock.set(START);
		Project project = fixtures.project();
		environment = fixtures.environment(project, Map.of());
		suite = fixtures.suite(project);
		fixtures.testCase(suite, HttpMethod.POST, "/orders", Map.of("X-Trace", List.of("trace-1")), """
				{"item": 1}""");
		executor.respond(200, Map.of("Content-Type", List.of("application/json")), """
				{"orderId": 7}""");
		tester = fixtures.user().getId();
	}

	@AfterEach
	void cleanUp() {
		fixtures.deleteAll();
		executor.reset();
	}

	@Test
	void clearsRawPayloadOfExpiredRunsButKeepsTheRecord() {
		long expired = runAt(START);
		long recent = runAt(START.plus(Duration.ofDays(25)));
		Instant now = START.plus(Duration.ofDays(30)).plus(Duration.ofHours(1));
		clock.set(now);

		RawResultRetention.Purged purged = retention.purgeExpired();

		assertThat(purged).isEqualTo(new RawResultRetention.Purged(1, 1));
		TestRunDetail detail = testRunService.getRun(expired);
		assertThat(detail.rawPurgedAt()).isEqualTo(now);
		assertThat(detail.summary()).isEqualTo(new TestRunDetail.Summary(1, 1, 0, 0));
		TestRunDetail.Result result = detail.results().getFirst();
		// 원문만 비움
		assertThat(result.request().headers()).isNull();
		assertThat(result.request().body()).isNull();
		assertThat(result.response().headers()).isNull();
		assertThat(result.response().body()).isNull();
		// 판정과 요약 정보는 유지
		assertThat(result.verdict()).isEqualTo(TestResult.Verdict.PASSED);
		assertThat(result.request().method()).isEqualTo(HttpMethod.POST);
		assertThat(result.request().url()).isEqualTo("https://gw.dev.internal/orders");
		assertThat(result.response().statusCode()).isEqualTo(200);
		assertThat(result.elapsedMs()).isEqualTo(12);

		TestRunDetail untouched = testRunService.getRun(recent);
		assertThat(untouched.rawPurgedAt()).isNull();
		assertThat(untouched.results().getFirst().request().body()).isEqualTo("""
				{"item": 1}""");
		assertThat(untouched.results().getFirst().response().body()).isEqualTo("""
				{"orderId": 7}""");

		// 이미 정리한 실행은 다시 대상이 되지 않음
		assertThat(retention.purgeExpired()).isEqualTo(new RawResultRetention.Purged(0, 0));
	}

	@Test
	void keepsRunsThatStartedWithinTheRetentionPeriod() {
		long boundary = runAt(START);
		clock.set(START.plus(Duration.ofDays(30)));

		// 정확히 30일 전에 시작한 실행은 아직 보관
		assertThat(retention.purgeExpired()).isEqualTo(new RawResultRetention.Purged(0, 0));
		assertThat(testRunService.getRun(boundary).rawPurgedAt()).isNull();
	}

	@Test
	void purgesInBatchesUntilNothingIsLeft() {
		List<Long> expired = List.of(runAt(START), runAt(START.plusSeconds(1)), runAt(START.plusSeconds(2)),
				runAt(START.plusSeconds(3)), runAt(START.plusSeconds(4)));
		clock.set(START.plus(Duration.ofDays(31)));

		// 배치 크기 2: 2 + 2 + 1
		assertThat(retention.purgeExpired()).isEqualTo(new RawResultRetention.Purged(5, 5));
		assertThat(runs.findAllById(expired)).allSatisfy(run -> assertThat(run.getRawPurgedAt()).isNotNull());
	}

	@Test
	void skipsRunsThatAreStillRunning() {
		TestRun running = runs.save(TestRun.start(suite, environment, TestRun.Trigger.SCHEDULED, START));
		clock.set(START.plus(Duration.ofDays(31)));

		assertThat(retention.purgeExpired()).isEqualTo(new RawResultRetention.Purged(0, 0));
		assertThat(runs.findById(running.getId()).orElseThrow().getRawPurgedAt()).isNull();
	}

	@Test
	void registersTheNightlyPurge() {
		assertThat(taskHolders.stream().flatMap(holder -> holder.getScheduledTasks().stream()))
			.map(task -> task.getTask())
			.filteredOn(CronTask.class::isInstance)
			.extracting(task -> ((CronTask) task).getExpression())
			.contains("0 30 3 * * *");
	}

	@Test
	void rejectsNonPositiveRetention() {
		assertThatThrownBy(() -> new RetentionProperties(true, Duration.ZERO, "0 30 3 * * *", 500))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("raw-retention");
		assertThatThrownBy(() -> new RetentionProperties(true, Duration.ofDays(30), "0 30 3 * * *", 0))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("batch-size");
	}

	private long runAt(Instant startedAt) {
		clock.set(startedAt);
		return testRunService.runSuite(suite.getId(), environment.getId(), TestRun.Trigger.MANUAL, tester);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class ClockConfiguration {

		@Bean
		@Primary
		MutableClock mutableClock() {
			return new MutableClock(START);
		}

	}

}
