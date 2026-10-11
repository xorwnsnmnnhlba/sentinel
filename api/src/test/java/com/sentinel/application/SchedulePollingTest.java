package com.sentinel.application;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.sentinel.TestcontainersConfiguration;
import com.sentinel.application.notification.Notification;
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
@SpringBootTest(properties = { "sentinel.schedule.enabled=false",
		"sentinel.notification.web-base-url=http://sentinel.test" })
@Import({ TestcontainersConfiguration.class, FakeRequestExecutorConfiguration.class, TestFixtures.class,
		SchedulePollingTest.ClockConfiguration.class })
class SchedulePollingTest {

	/** 변경 이력에 남길 사용자. 이력 자체는 이 테스트의 대상이 아님 */
	private static final long ACTOR = 1L;

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

	@Autowired
	private MaintenanceWindowService maintenanceWindows;

	@Autowired
	private MonitoringGapService monitoringGaps;

	@Autowired
	private HolidayService holidays;

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
		ScheduleDetail schedule = scheduleService.create(suite.getId(), environment.getId(), "10 9 * * *", ACTOR);
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
		ScheduleDetail schedule = scheduleService.create(suite.getId(), environment.getId(), "*/10 * * * *", ACTOR);
		clock.advance(Duration.ofHours(3));

		assertThat(poller.poll()).hasSize(1);
		assertThat(poller.poll()).isEmpty();
		assertThat(scheduleService.get(schedule.id()).nextRunAt()).isEqualTo("2026-09-18T03:10:00Z");
	}

	@Test
	void skipsDisabledScheduleAndResumesAfterEnable() {
		ScheduleDetail schedule = scheduleService.create(suite.getId(), environment.getId(), "10 9 * * *", ACTOR);
		scheduleService.update(schedule.id(), false, null, null, ACTOR);
		clock.set(Instant.parse("2026-09-18T01:00:00Z"));

		assertThat(poller.poll()).isEmpty();

		ScheduleDetail enabled = scheduleService.update(schedule.id(), true, null, null, ACTOR);
		assertThat(enabled.nextRunAt()).isEqualTo("2026-09-19T00:10:00Z");
	}

	@Test
	void skipsSchedulesOfAnEnvironmentUnderMaintenanceAndResumesAfterIt() {
		ScheduleDetail schedule = scheduleService.create(suite.getId(), environment.getId(), "*/10 * * * *", ACTOR);
		// 다른 환경의 스케줄은 그대로 실행
		Environment other = fixtures.environment(suite.getProject(), "other", Map.of());
		scheduleService.create(suite.getId(), other.getId(), "*/10 * * * *", ACTOR);
		// 09:10 ~ 09:30 점검
		maintenanceWindows.create(environment.getId(), Instant.parse("2026-09-18T00:10:00Z"),
				Instant.parse("2026-09-18T00:30:00Z"), "GW 배포", ACTOR);

		clock.set(Instant.parse("2026-09-18T00:10:00Z"));
		List<Long> duringMaintenance = poller.poll();

		// 점검 중인 환경은 실행 기록 없이 다음 실행 시각으로 넘어가고 마지막 실행 시각은 그대로
		assertThat(duringMaintenance).hasSize(1);
		assertThat(testRunService.getRun(duringMaintenance.getFirst()).environment().id()).isEqualTo(other.getId());
		ScheduleDetail skipped = scheduleService.get(schedule.id());
		assertThat(skipped.nextRunAt()).isEqualTo("2026-09-18T00:20:00Z");
		assertThat(skipped.lastTriggeredAt()).isNull();
		assertThat(notifier.sent()).isEmpty();

		// 끝 시각부터는 다시 실행 (끝은 미포함)
		clock.set(Instant.parse("2026-09-18T00:30:00Z"));
		assertThat(poller.poll()).hasSize(2);
		assertThat(scheduleService.get(schedule.id()).lastTriggeredAt()).isEqualTo("2026-09-18T00:30:00Z");
	}

	@Test
	void runsBeforeAPlannedMaintenanceAndAfterItIsDeleted() {
		ScheduleDetail schedule = scheduleService.create(suite.getId(), environment.getId(), "*/10 * * * *", ACTOR);
		MaintenanceWindowItem window = maintenanceWindows.create(environment.getId(),
				Instant.parse("2026-09-18T00:20:00Z"), Instant.parse("2026-09-18T01:00:00Z"), null, ACTOR);

		// 시작 전
		clock.set(Instant.parse("2026-09-18T00:10:00Z"));
		assertThat(poller.poll()).hasSize(1);

		// 시작 시각부터 건너뜀 (시작은 포함)
		clock.set(Instant.parse("2026-09-18T00:20:00Z"));
		assertThat(poller.poll()).isEmpty();

		// 점검을 삭제하면 바로 다음 스케줄부터 실행
		maintenanceWindows.delete(window.id(), ACTOR);
		clock.set(Instant.parse("2026-09-18T00:30:00Z"));
		assertThat(poller.poll()).hasSize(1);
		assertThat(scheduleService.get(schedule.id()).nextRunAt()).isEqualTo("2026-09-18T00:40:00Z");
	}

	@Test
	void skipsHolidaysOnlyForSchedulesThatOptIn() {
		ScheduleDetail skipping = scheduleService.create(suite.getId(), environment.getId(), "10 9 * * *", true, ACTOR);
		ScheduleDetail running = scheduleService.create(suite.getId(), environment.getId(), "10 9 * * *", ACTOR);
		// 2026-09-18(금)만 휴일. 휴일 판단은 서버 설정 시간대(KST) 날짜 기준
		holidays.create(LocalDate.parse("2026-09-18"), "임시공휴일", ACTOR);

		clock.set(Instant.parse("2026-09-18T00:10:00Z"));
		List<Long> onHoliday = poller.poll();

		// 건너뛴 스케줄은 실행 기록·알림 없이 다음 날로 넘어가고 마지막 실행 시각은 그대로
		assertThat(onHoliday).hasSize(1);
		assertThat(scheduleService.get(running.id()).lastTriggeredAt()).isEqualTo("2026-09-18T00:10:00Z");
		ScheduleDetail skipped = scheduleService.get(skipping.id());
		assertThat(skipped.nextRunAt()).isEqualTo("2026-09-19T00:10:00Z");
		assertThat(skipped.lastTriggeredAt()).isNull();
		assertThat(notifier.sent()).isEmpty();
		assertThat(monitoringGaps.list(LocalDate.parse("2026-09-18"), LocalDate.parse("2026-09-19")).gaps()).isEmpty();

		// 다음 날은 실행
		clock.set(Instant.parse("2026-09-19T00:10:00Z"));
		assertThat(poller.poll()).hasSize(2);
		assertThat(scheduleService.get(skipping.id()).lastTriggeredAt()).isEqualTo("2026-09-19T00:10:00Z");
	}

	@Test
	void judgesTheDateInTheServerZoneAndRunsOnceTheHolidayIsDeletedOrSkippingIsOff() {
		// 매일 08:50 KST = 전날 23:50 UTC
		ScheduleDetail schedule = scheduleService.create(suite.getId(), environment.getId(), "50 8 * * *", true, ACTOR);
		holidays.create(LocalDate.parse("2026-09-19"), "토요일", ACTOR);
		long sunday = holidays.create(LocalDate.parse("2026-09-20"), "일요일", ACTOR).id();
		holidays.create(LocalDate.parse("2026-09-21"), "월요일", ACTOR);

		// UTC로는 18일이지만 KST로는 19일(휴일)
		clock.set(Instant.parse("2026-09-18T23:50:00Z"));
		assertThat(poller.poll()).isEmpty();
		assertThat(scheduleService.get(schedule.id()).nextRunAt()).isEqualTo("2026-09-19T23:50:00Z");

		// 삭제한 휴일에는 실행
		holidays.delete(sunday, ACTOR);
		clock.set(Instant.parse("2026-09-19T23:50:00Z"));
		assertThat(poller.poll()).hasSize(1);

		// 건너뛰기를 끄면 휴일에도 실행
		scheduleService.update(schedule.id(), null, null, null, false, ACTOR);
		clock.set(Instant.parse("2026-09-20T23:50:00Z"));
		assertThat(poller.poll()).hasSize(1);
	}

	@Test
	void bulkEnablingLeavesAlreadyEnabledSchedulesDue() {
		ScheduleDetail due = scheduleService.create(suite.getId(), environment.getId(), "10 9 * * *", ACTOR);
		ScheduleDetail off = scheduleService.create(suite.getId(), environment.getId(), "10 9 * * *", ACTOR);
		scheduleService.update(off.id(), false, null, null, ACTOR);
		// 09:10 예정이 아직 폴링되지 않은 사이에 일괄로 켬
		clock.set(Instant.parse("2026-09-18T00:10:30Z"));

		scheduleService.updateAll(List.of(due.id(), off.id()), true, null, ACTOR);

		// 켜져 있던 스케줄은 예정 그대로라 이번 폴링에 실행, 꺼져 있던 스케줄은 지금 기준으로 다음 날
		assertThat(scheduleService.get(due.id()).nextRunAt()).isEqualTo("2026-09-18T00:10:00Z");
		assertThat(scheduleService.get(off.id()).nextRunAt()).isEqualTo("2026-09-19T00:10:00Z");
		assertThat(poller.poll()).hasSize(1);
	}

	@Test
	void recordsAndNotifiesMonitoringGapWhenSchedulesRunLate() {
		scheduleService.create(suite.getId(), environment.getId(), "*/10 * * * *", ACTOR);
		scheduleService.create(suite.getId(), environment.getId(), "30 9 * * *", ACTOR);
		// 11:35 예정은 5분 늦어 공백에 넣지 않음
		scheduleService.create(suite.getId(), environment.getId(), "35 11 * * *", ACTOR);
		// 09:10 이후 폴링이 멈췄다가 11:40에 재개 (Sentinel 중지·서버 절전 등)
		clock.set(Instant.parse("2026-09-18T02:40:00Z"));

		assertThat(poller.poll()).hasSize(3);

		assertThat(notifier.sent()).singleElement().satisfies(notification -> {
			assertThat(notification.title()).isEqualTo("[Sentinel] 모니터링 공백 - 스케줄이 제때 실행되지 않음");
			assertThat(notification.lines()).containsExactly(
					"기간: 2026-09-18 09:10 (Asia/Seoul) ~ 2026-09-18 11:40 (Asia/Seoul) (2시간 30분)",
					"제때 실행되지 않은 스케줄: 2건", "놓친 실행은 스케줄마다 1회씩 지금 실행합니다", "원인 예: Sentinel 중지, 서버 절전, 오래 걸린 앞 실행");
		});
		assertThat(monitoringGaps.list(LocalDate.of(2026, 9, 18), LocalDate.of(2026, 9, 18)).gaps())
			.singleElement()
			.satisfies(gap -> {
				assertThat(gap.startedAt()).isEqualTo("2026-09-18T00:10:00Z");
				assertThat(gap.endedAt()).isEqualTo("2026-09-18T02:40:00Z");
				assertThat(gap.missedSchedules()).isEqualTo(2);
			});
	}

	@Test
	void ignoresDelaysWithinTheGapThreshold() {
		scheduleService.create(suite.getId(), environment.getId(), "*/10 * * * *", ACTOR);
		scheduleService.create(suite.getId(), environment.getId(), "20 9 * * *", ACTOR);
		// 09:10 예정이 정확히 10분(기준) 늦음. 09:20 예정은 늦지 않음
		clock.set(Instant.parse("2026-09-18T00:20:00Z"));

		assertThat(poller.poll()).hasSize(2);

		assertThat(notifier.sent()).isEmpty();
		assertThat(monitoringGaps.list(null, null).gaps()).isEmpty();
	}

	@Test
	void countsOnlyLateSchedulesAndStartsGapAtTheEarliestMissedTime() {
		scheduleService.create(suite.getId(), environment.getId(), "10 9 * * *", ACTOR);
		scheduleService.create(suite.getId(), environment.getId(), "40 9 * * *", ACTOR);
		// 09:10 예정은 40분, 09:40 예정은 10분 늦음 (기준 이하)
		clock.set(Instant.parse("2026-09-18T00:50:00Z"));

		assertThat(poller.poll()).hasSize(2);

		assertThat(monitoringGaps.list(null, null).gaps()).singleElement().satisfies(gap -> {
			assertThat(gap.startedAt()).isEqualTo("2026-09-18T00:10:00Z");
			assertThat(gap.missedSchedules()).isEqualTo(1);
		});
	}

	@Test
	void extendsGapThatResumesWithinTheThresholdWithoutNotifyingAgain() {
		scheduleService.create(suite.getId(), environment.getId(), "*/10 * * * *", ACTOR);
		clock.set(Instant.parse("2026-09-18T01:00:00Z"));
		poller.poll();
		// 밀린 실행 처리로 다음 폴링이 늦어져 01:10 예정이 01:25에 실행 (앞 공백이 끝난 뒤 10분 안에 시작)
		clock.set(Instant.parse("2026-09-18T01:25:00Z"));
		poller.poll();

		assertThat(notifier.sent()).hasSize(1);
		assertThat(monitoringGaps.list(null, null).gaps()).singleElement().satisfies(gap -> {
			assertThat(gap.startedAt()).isEqualTo("2026-09-18T00:10:00Z");
			assertThat(gap.endedAt()).isEqualTo("2026-09-18T01:25:00Z");
		});

		// 제때 실행 뒤 끝난 시각 + 기준 시간(01:35)이 지나 시작된 공백은 새 공백
		clock.set(Instant.parse("2026-09-18T01:30:00Z"));
		poller.poll();
		clock.set(Instant.parse("2026-09-18T03:00:00Z"));
		poller.poll();

		assertThat(notifier.sent()).hasSize(2);
		assertThat(monitoringGaps.list(null, null).gaps()).extracting(MonitoringGapService.Gap::startedAt)
			.containsExactly(Instant.parse("2026-09-18T01:40:00Z"), Instant.parse("2026-09-18T00:10:00Z"));

		// 가장 최근 공백(03:00에 끝남)에 이어짐. 오래된 공백과는 비교하지 않음
		clock.set(Instant.parse("2026-09-18T03:25:00Z"));
		poller.poll();

		assertThat(notifier.sent()).hasSize(2);
		assertThat(monitoringGaps.list(null, null).gaps()).extracting(MonitoringGapService.Gap::endedAt)
			.containsExactly(Instant.parse("2026-09-18T03:25:00Z"), Instant.parse("2026-09-18T01:25:00Z"));
	}

	@Test
	void doesNotRecordGapForSchedulesSkippedDuringMaintenance() {
		scheduleService.create(suite.getId(), environment.getId(), "*/10 * * * *", ACTOR);
		maintenanceWindows.create(environment.getId(), Instant.parse("2026-09-18T00:10:00Z"),
				Instant.parse("2026-09-18T02:00:00Z"), null, ACTOR);

		for (Instant at = Instant.parse("2026-09-18T00:10:00Z"); !at.isAfter(Instant.parse("2026-09-18T02:10:00Z")); at = at
			.plus(Duration.ofMinutes(1))) {
			clock.set(at);
			poller.poll();
		}

		assertThat(notifier.sent()).isEmpty();
		assertThat(monitoringGaps.list(null, null).gaps()).isEmpty();
	}

	@Test
	void notifiesOnlyWhenScheduledRunHasFailures() {
		fixtures.testCase(suite, HttpMethod.POST, "/billing/v1/charge", null, "{}",
				List.of(new StatusCodeAssertion(List.of("201"))));
		scheduleService.create(suite.getId(), environment.getId(), "10 9 * * *", ACTOR);
		clock.set(Instant.parse("2026-09-18T00:10:00Z"));

		List<Long> runIds = poller.poll();

		assertThat(notifier.sent()).singleElement().satisfies(notification -> {
			assertThat(notification.title()).startsWith("[Sentinel] dev suite-").endsWith(" - 2건 중 1건 실패 (새로 실패 1건)");
			assertThat(notification.lines()).hasSize(2);
			assertThat(notification.lines().getFirst()).startsWith("[새로 실패] case-")
				.endsWith(" · POST /billing/v1/charge → status in [201]: was 200");
			assertThat(notification.lines().get(1)).isEqualTo("계속 실패하면 1시간마다 다시 알림 (새로 실패·복구는 바로)");
			assertThat(notification.tone()).isEqualTo(Notification.Tone.FAILURE);
			assertThat(notification.links())
				.containsExactly(new Notification.Link("결과", "http://sentinel.test/runs/" + runIds.getFirst()));
		});
	}

	@Test
	void sendsNothingWhenScheduledRunPasses() {
		scheduleService.create(suite.getId(), environment.getId(), "10 9 * * *", ACTOR);
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
