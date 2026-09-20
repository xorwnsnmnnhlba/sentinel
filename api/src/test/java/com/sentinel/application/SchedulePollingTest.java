package com.sentinel.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.sentinel.TestcontainersConfiguration;
import com.sentinel.application.notification.RecordingNotifier;
import com.sentinel.domain.Environment;
import com.sentinel.domain.Project;
import com.sentinel.domain.TestRun;
import com.sentinel.domain.TestSuite;
import com.sentinel.infra.persistence.TestRunRepository;
import com.sentinel.runner.HttpMethod;
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
 * 자동 폴링을 끄고 {@link SchedulePoller#poll()}을 직접 호출하여 시각을 제어.
 */
@SpringBootTest(properties = "sentinel.schedule.enabled=false")
@Import({ TestcontainersConfiguration.class, FakeRequestExecutorConfiguration.class, TestFixtures.class,
		SchedulePollingTest.ClockConfiguration.class })
class SchedulePollingTest {

	/** 2026-09-18 09:00 KST */
	private static final Instant NINE_AM_KST = Instant.parse("2026-09-18T00:00:00Z");

	@Autowired
	private SchedulePoller poller;

	@Autowired
	private ScheduleService scheduleService;

	@Autowired
	private TestRunService testRunService;

	@Autowired
	private StaleRunRecovery staleRunRecovery;

	@Autowired
	private TestRunRepository runs;

	@Autowired
	private FakeRequestExecutor executor;

	@Autowired
	private TestFixtures fixtures;

	@Autowired
	private MutableClock clock;

	@Autowired
	private RecordingNotifier notifier;

	private TestSuite suite;

	private Environment environment;

	@BeforeEach
	void setUp() {
		clock.set(NINE_AM_KST);
		Project project = fixtures.project();
		environment = fixtures.environment(project, Map.of());
		suite = fixtures.suite(project);
		fixtures.testCase(suite, HttpMethod.GET, "/health", null, null);
	}

	@AfterEach
	void cleanUp() {
		fixtures.deleteAll();
		executor.reset();
		notifier.reset();
	}

	@Test
	void runsSuiteWhenDueAndMovesToNextDay() {
		ScheduleDetail schedule = scheduleService.create(suite.getId(), environment.getId(), "10 9 * * *");
		assertThat(schedule.nextRunAt()).isEqualTo("2026-09-18T00:10:00Z");
		assertThat(schedule.zone()).isEqualTo("Asia/Seoul");

		clock.set(Instant.parse("2026-09-18T00:09:59Z"));
		assertThat(poller.poll()).isEmpty();

		clock.set(Instant.parse("2026-09-18T00:10:30Z"));
		List<Long> runIds = poller.poll();

		assertThat(runIds).hasSize(1);
		TestRunDetail run = testRunService.getRun(runIds.getFirst());
		assertThat(run.trigger()).isEqualTo(TestRun.Trigger.SCHEDULED);
		assertThat(run.status()).isEqualTo(TestRun.Status.COMPLETED);
		assertThat(run.summary().total()).isEqualTo(1);
		assertThat(executor.requests()).hasSize(1);

		ScheduleDetail after = scheduleService.get(schedule.id());
		assertThat(after.lastTriggeredAt()).isEqualTo("2026-09-18T00:10:30Z");
		assertThat(after.nextRunAt()).isEqualTo("2026-09-19T00:10:00Z");
		assertThat(poller.poll()).isEmpty();
	}

	@Test
	void runsMissedScheduleOnlyOnce() {
		ScheduleDetail schedule = scheduleService.create(suite.getId(), environment.getId(), "*/10 * * * *");
		clock.advance(Duration.ofHours(3));

		assertThat(poller.poll()).hasSize(1);
		assertThat(poller.poll()).isEmpty();
		assertThat(scheduleService.get(schedule.id()).nextRunAt()).isEqualTo("2026-09-18T03:10:00Z");
	}

	@Test
	void skipsDisabledScheduleAndResumesAfterEnable() {
		ScheduleDetail schedule = scheduleService.create(suite.getId(), environment.getId(), "10 9 * * *");
		scheduleService.update(schedule.id(), false, null, null);
		clock.set(Instant.parse("2026-09-18T01:00:00Z"));

		assertThat(poller.poll()).isEmpty();

		ScheduleDetail enabled = scheduleService.update(schedule.id(), true, null, null);
		assertThat(enabled.nextRunAt()).isEqualTo("2026-09-19T00:10:00Z");
	}

	@Test
	void notifiesOnlyWhenScheduledRunHasFailures() {
		fixtures.testCase(suite, HttpMethod.POST, "/billing/v1/charge", null, "{}",
				List.of(new StatusCodeAssertion(List.of("201"))));
		scheduleService.create(suite.getId(), environment.getId(), "10 9 * * *");
		clock.set(Instant.parse("2026-09-18T00:10:00Z"));

		poller.poll();

		assertThat(notifier.sent()).singleElement().satisfies(notification -> {
			assertThat(notification.title()).startsWith("[Sentinel] dev suite-").endsWith(" - 2건 중 1건 실패");
			assertThat(notification.lines())
				.containsExactly("POST   /billing/v1/charge → status in [201]: was 200");
		});
	}

	@Test
	void sendsNothingWhenScheduledRunPasses() {
		scheduleService.create(suite.getId(), environment.getId(), "10 9 * * *");
		clock.set(Instant.parse("2026-09-18T00:10:00Z"));

		assertThat(poller.poll()).hasSize(1);
		assertThat(notifier.sent()).isEmpty();
	}

	@Test
	void abortsRunsLeftRunning() {
		TestRun stale = runs.save(TestRun.start(suite, environment, TestRun.Trigger.SCHEDULED, NINE_AM_KST));
		clock.set(Instant.parse("2026-09-18T02:00:00Z"));

		assertThat(staleRunRecovery.abortStaleRuns()).isEqualTo(1);

		TestRunDetail run = testRunService.getRun(stale.getId());
		assertThat(run.status()).isEqualTo(TestRun.Status.ABORTED);
		assertThat(run.finishedAt()).isEqualTo("2026-09-18T02:00:00Z");
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class ClockConfiguration {

		@Bean
		@Primary
		MutableClock mutableClock() {
			return new MutableClock(NINE_AM_KST);
		}

		@Bean
		@Primary
		RecordingNotifier recordingNotifier() {
			return new RecordingNotifier();
		}

	}

}
