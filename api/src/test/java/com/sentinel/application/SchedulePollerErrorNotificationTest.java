package com.sentinel.application;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import com.sentinel.application.notification.Notification;
import com.sentinel.application.notification.NotificationProperties;
import com.sentinel.application.notification.RecordingNotifier;
import com.sentinel.application.notification.RunResultNotifier;
import com.sentinel.domain.Environment;
import com.sentinel.domain.TestSchedule;
import com.sentinel.domain.TestSuite;
import com.sentinel.infra.persistence.HolidayRepository;
import com.sentinel.infra.persistence.MaintenanceWindowRepository;
import com.sentinel.infra.persistence.TestScheduleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 스케줄 실행 오류 알림 (2026-10-12 독립 리뷰)
 * <ul>
 * <li>시작조차 못 하는 스케줄(시크릿을 풀 수 없음 등)은 같은 오류를 재알림 간격마다 한 번만 알림 (예전: 매 주기)</li>
 * <li>폴링 중 사용자가 스케줄을 고친 낙관적 잠금 충돌은 오류가 아니므로 알림 없음 (다음 주기에 다시 판단)</li>
 * </ul>
 */
class SchedulePollerErrorNotificationTest {

	private static final Instant NOW = Instant.parse("2026-09-19T00:00:00Z");

	private final TestScheduleRepository schedules = mock(TestScheduleRepository.class);

	private final TestRunService testRunService = mock(TestRunService.class);

	private final MaintenanceWindowRepository maintenanceWindows = mock(MaintenanceWindowRepository.class);

	private final RecordingNotifier notifier = new RecordingNotifier();

	private final MutableClock clock = new MutableClock(NOW);

	private SchedulePoller poller;

	@BeforeEach
	void setUp() {
		PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
		when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
		poller = new SchedulePoller(schedules, testRunService, mock(RunResultNotifier.class), notifier,
				new ScheduleProperties(true, ZoneId.of("Asia/Seoul"), Duration.ofSeconds(60), Duration.ofMinutes(10)), clock,
				new Transactions(transactionManager), maintenanceWindows, mock(MonitoringGapService.class), Heartbeat.NONE,
				mock(HolidayRepository.class),
				new NotificationProperties(null, null, Duration.ofSeconds(3), Duration.ofSeconds(5), Duration.ofHours(1)));
		dueSchedule();
	}

	@Test
	void notifiesTheSameErrorOncePerRepeatInterval() {
		failToStart("secret 'token' cannot be decrypted");

		poller.poll();
		clock.advance(Duration.ofMinutes(1));
		poller.poll();
		clock.advance(Duration.ofMinutes(58));
		poller.poll();

		assertThat(notifier.sent()).singleElement()
			.satisfies(sent -> assertThat(sent.lines()).contains("사유: secret 'token' cannot be decrypted"));

		// 재알림 간격(1시간)이 지나면 다시
		clock.advance(Duration.ofMinutes(2));
		poller.poll();
		assertThat(notifier.sent()).hasSize(2);
	}

	@Test
	void notifiesRightAwayWhenTheReasonChangesOrAfterTheScheduleRecovers() {
		failToStart("secret 'token' cannot be decrypted");
		poller.poll();

		failToStart("environment 3 not found");
		clock.advance(Duration.ofMinutes(1));
		poller.poll();
		assertThat(notifier.sent()).extracting(Notification::lines)
			.anySatisfy(lines -> assertThat(lines).contains("사유: environment 3 not found"))
			.hasSize(2);

		// 정상 실행되면 기억을 지워, 다시 실패하면 같은 사유여도 바로 알림
		doReturn(10L).when(testRunService).runSuite(anyLong(), anyLong(), any(), any());
		clock.advance(Duration.ofMinutes(1));
		poller.poll();
		failToStart("environment 3 not found");
		clock.advance(Duration.ofMinutes(1));
		poller.poll();
		assertThat(notifier.sent()).hasSize(3);
	}

	@Test
	void sendsNoErrorWhenTheScheduleChangedWhileBeingClaimed() {
		when(maintenanceWindows.findActive(anyLong(), any()))
			.thenThrow(new ObjectOptimisticLockingFailureException(TestSchedule.class, 1L));

		poller.poll();

		assertThat(notifier.sent()).isEmpty();
	}

	/** 실행할 차례인 스케줄 1건 (점검·휴일 아님) */
	private void dueSchedule() {
		TestSchedule schedule = mock(TestSchedule.class);
		when(schedule.getId()).thenReturn(1L);
		when(schedule.getNextRunAt()).thenReturn(NOW.minusSeconds(30));
		when(schedule.isDue(any())).thenReturn(true);
		Environment environment = mock(Environment.class);
		when(environment.getId()).thenReturn(3L);
		when(schedule.getEnvironment()).thenReturn(environment);
		TestSuite suite = mock(TestSuite.class);
		when(suite.getId()).thenReturn(2L);
		when(schedule.getSuite()).thenReturn(suite);
		doReturn(List.of(schedule)).when(schedules).findByEnabledTrueAndNextRunAtLessThanEqualOrderByNextRunAt(any());
		when(schedules.findById(eq(1L))).thenReturn(Optional.of(schedule));
		when(maintenanceWindows.findActive(anyLong(), any())).thenReturn(Optional.empty());
	}

	private void failToStart(String reason) {
		// when(...) 형태는 다시 지정할 때 앞의 예외가 실제로 발생하므로 doThrow 형태
		doThrow(new InvalidRequestException(reason)).when(testRunService).runSuite(anyLong(), anyLong(), any(), any());
	}

}
