package com.sentinel.application;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import com.sentinel.application.notification.Notification;
import com.sentinel.application.notification.RecordingNotifier;
import com.sentinel.application.notification.RunResultNotifier;
import com.sentinel.domain.TestSchedule;
import com.sentinel.infra.persistence.MaintenanceWindowRepository;
import com.sentinel.infra.persistence.TestScheduleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DB 접속 실패는 컨테이너로 재현하기 어려우므로 리포지토리 예외로 모사.
 */
class SchedulePollerDatabaseOutageTest {

	private final TestScheduleRepository schedules = mock(TestScheduleRepository.class);

	private final RecordingNotifier notifier = new RecordingNotifier();

	private final MutableClock clock = new MutableClock(Instant.parse("2026-09-19T00:00:00Z"));

	private final MonitoringGapService monitoringGaps = mock(MonitoringGapService.class);

	private SchedulePoller poller;

	@BeforeEach
	void setUp() {
		PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
		when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
		poller = new SchedulePoller(schedules, mock(TestRunService.class), mock(RunResultNotifier.class), notifier,
				new ScheduleProperties(true, ZoneId.of("Asia/Seoul"), Duration.ofSeconds(60), Duration.ofMinutes(10)), clock,
				new Transactions(transactionManager), mock(MaintenanceWindowRepository.class), monitoringGaps);
	}

	@Test
	void notifiesOnceWhenDatabaseGoesDownAndOnceWhenItRecovers() {
		databaseDown();
		assertThat(poller.poll()).isEmpty();
		clock.advance(Duration.ofMinutes(1));
		poller.poll();
		clock.advance(Duration.ofMinutes(1));
		poller.poll();

		assertThat(notifier.sent()).singleElement().satisfies(notification -> {
			assertThat(notification.title()).isEqualTo("[Sentinel] DB 연결 실패 - 스케줄 실행 중단");
			assertThat(notification.lines()).contains("발생 시각: 2026-09-19 09:00 (Asia/Seoul)",
					"사유: Connection refused");
		});

		databaseUp();
		clock.advance(Duration.ofMinutes(28));
		poller.poll();
		poller.poll();

		List<Notification> sent = notifier.sent();
		assertThat(sent).hasSize(2);
		assertThat(sent.get(1).title()).isEqualTo("[Sentinel] DB 연결 복구 - 스케줄 실행 재개");
		assertThat(sent.get(1).lines()).first()
			.isEqualTo("중단 기간: 2026-09-19 09:00 (Asia/Seoul) ~ 2026-09-19 09:30 (Asia/Seoul) (30분)");
	}

	@Test
	void recordsGapAfterRecoveryButOnlySendsTheRecoveryNotification() {
		databaseDown();
		poller.poll();
		clock.advance(Duration.ofMinutes(30));
		dueSchedule(Instant.parse("2026-09-19T00:05:00Z"));
		when(monitoringGaps.record(any(), any(), anyInt())).thenReturn(true);

		poller.poll();

		verify(monitoringGaps).record(Instant.parse("2026-09-19T00:05:00Z"), Instant.parse("2026-09-19T00:30:00Z"), 1);
		assertThat(notifier.sent()).extracting(Notification::title)
			.containsExactly("[Sentinel] DB 연결 실패 - 스케줄 실행 중단", "[Sentinel] DB 연결 복구 - 스케줄 실행 재개");
	}

	@Test
	void stillNotifiesGapWhenRecordingItFails() {
		dueSchedule(Instant.parse("2026-09-18T23:00:00Z"));
		when(monitoringGaps.record(any(), any(), anyInt())).thenThrow(new IllegalStateException("disk full"));

		poller.poll();

		assertThat(notifier.sent()).singleElement()
			.extracting(Notification::title)
			.isEqualTo("[Sentinel] 모니터링 공백 - 스케줄이 제때 실행되지 않음");
	}

	@Test
	void doesNotNotifyGapThatContinuesThePreviousOne() {
		dueSchedule(Instant.parse("2026-09-18T23:00:00Z"));
		when(monitoringGaps.record(any(), any(), anyInt())).thenReturn(false);

		poller.poll();

		assertThat(notifier.sent()).isEmpty();
	}

	@Test
	void reportsMostSpecificCauseOfDatabaseFailure() {
		doThrow(new DataAccessResourceFailureException("Could not open JPA EntityManager for transaction",
				new java.sql.SQLTransientConnectionException("Connection is not available, request timed out after 10009ms")))
			.when(schedules)
			.findByEnabledTrueAndNextRunAtLessThanEqualOrderByNextRunAt(any());

		poller.poll();

		assertThat(notifier.sent().getFirst().lines())
			.contains("사유: Connection is not available, request timed out after 10009ms");
	}

	@Test
	void formatsOutageDurationBySize() {
		assertThat(SchedulePoller.formatDuration(Duration.ofSeconds(48))).isEqualTo("48초");
		assertThat(SchedulePoller.formatDuration(Duration.ofMinutes(30))).isEqualTo("30분");
		assertThat(SchedulePoller.formatDuration(Duration.ofMinutes(135))).isEqualTo("2시간 15분");
	}

	@Test
	void sendsNothingWhileDatabaseIsHealthy() {
		databaseUp();

		poller.poll();
		poller.poll();

		assertThat(notifier.sent()).isEmpty();
	}

	@Test
	void keepsPollingWhenNotifierFails() {
		PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
		when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
		SchedulePoller failingNotifierPoller = new SchedulePoller(schedules, mock(TestRunService.class),
				mock(RunResultNotifier.class), notification -> {
					throw new IllegalStateException("webhook down");
				}, new ScheduleProperties(true, ZoneId.of("Asia/Seoul"), Duration.ofSeconds(60), Duration.ofMinutes(10)), clock,
				new Transactions(transactionManager), mock(MaintenanceWindowRepository.class), mock(MonitoringGapService.class));
		databaseDown();

		assertThat(failingNotifierPoller.poll()).isEmpty();
	}

	// doThrow/doReturn 형태: 이미 예외를 던지도록 설정된 메서드를 재설정할 때 when()은 실제 호출이 일어나 예외 발생
	private void databaseDown() {
		doThrow(new DataAccessResourceFailureException("Connection refused")).when(schedules)
			.findByEnabledTrueAndNextRunAtLessThanEqualOrderByNextRunAt(any());
	}

	/** 예정 시각이 {@code nextRunAt}인 스케줄 1건이 실행 시각 도달 (실행 대상 조회는 없음으로 처리) */
	private void dueSchedule(Instant nextRunAt) {
		TestSchedule schedule = mock(TestSchedule.class);
		when(schedule.getId()).thenReturn(1L);
		when(schedule.getNextRunAt()).thenReturn(nextRunAt);
		doReturn(List.of(schedule)).when(schedules).findByEnabledTrueAndNextRunAtLessThanEqualOrderByNextRunAt(any());
	}

	private void databaseUp() {
		doReturn(List.of()).when(schedules).findByEnabledTrueAndNextRunAtLessThanEqualOrderByNextRunAt(any());
	}

}
