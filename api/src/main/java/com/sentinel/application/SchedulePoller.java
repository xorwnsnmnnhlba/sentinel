package com.sentinel.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import com.sentinel.application.notification.Notification;
import com.sentinel.application.notification.NotificationFormat;
import com.sentinel.application.notification.Notifier;
import com.sentinel.application.notification.RunResultNotifier;
import com.sentinel.domain.TestRun;
import com.sentinel.domain.TestSchedule;
import com.sentinel.infra.persistence.TestScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

/**
 * DB 폴링 스케줄러. 실행 시각에 도달한 스케줄을 찾아 다음 실행 시각을 먼저 갱신한 뒤 스위트 실행.
 * <p>
 * DB 접속 실패(VPN 끊김 등)는 즉시 재시도해도 복구되지 않으므로 해당 주기를 건너뛰고 다음 주기 대기.
 * 접속 실패는 최초 1회, 복구 시 1회 알림 발송. 스케줄 실행 결과에 실패가 있으면 실패 알림 발송.
 * 알림 발송 실패는 로그만 남기고 폴링 계속.
 */
@Component
@RequiredArgsConstructor
public class SchedulePoller {

	private static final Logger log = LoggerFactory.getLogger(SchedulePoller.class);

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

	private final TestScheduleRepository schedules;

	private final TestRunService testRunService;

	private final RunResultNotifier runResultNotifier;

	private final Notifier notifier;

	private final ScheduleProperties properties;

	private final Clock clock;

	private final Transactions transactions;

	/** DB 접속 실패 시작 시각. 정상 시 null. 스케줄러 단일 스레드에서만 갱신 */
	private volatile Instant databaseUnavailableSince;

	/**
	 * @return 이번 주기에 생성된 실행 ID
	 */
	public List<Long> poll() {
		Instant now = clock.instant();
		List<Long> dueIds;
		try {
			dueIds = transactions.write(status -> schedules
				.findByEnabledTrueAndNextRunAtLessThanEqualOrderByNextRunAt(now)
				.stream()
				.map(TestSchedule::getId)
				.toList());
		}
		catch (DataAccessException | TransactionException ex) {
			String reason = ex.getMostSpecificCause().getMessage();
			log.warn("Skipping schedule poll at {}: database unavailable ({})", now, reason);
			onDatabaseUnavailable(now, reason);
			return List.of();
		}
		onDatabaseAvailable(now);

		List<Long> runIds = new ArrayList<>();
		for (Long scheduleId : dueIds) {
			try {
				Trigger trigger = transactions.write(status -> claim(scheduleId, now));
				if (trigger != null) {
					// 스케줄 실행은 주체 없음
					long runId = testRunService.runSuite(trigger.suiteId(), trigger.environmentId(),
							TestRun.Trigger.SCHEDULED, null);
					runIds.add(runId);
					notifyRunResult(runId);
				}
			}
			catch (RuntimeException ex) {
				log.error("Scheduled run failed for schedule {}", scheduleId, ex);
				send(new Notification("[Sentinel] 스케줄 " + scheduleId + " 실행 오류",
						List.of("시각: " + format(now), "사유: " + ex.getMessage())));
			}
		}
		return runIds;
	}

	private Trigger claim(long scheduleId, Instant now) {
		TestSchedule schedule = schedules.findById(scheduleId).orElse(null);
		if (schedule == null || !schedule.isDue(now)) {
			return null;
		}
		schedule.markTriggered(now, properties.zone());
		return new Trigger(schedule.getSuite().getId(), schedule.getEnvironment().getId());
	}

	private void onDatabaseUnavailable(Instant now, String reason) {
		if (databaseUnavailableSince != null) {
			return;
		}
		databaseUnavailableSince = now;
		send(new Notification("[Sentinel] DB 연결 실패 - 스케줄 실행 중단",
				List.of("발생 시각: " + format(now), "사유: " + reason, "복구될 때까지 스케줄 실행을 건너뜁니다")));
	}

	private void onDatabaseAvailable(Instant now) {
		Instant since = databaseUnavailableSince;
		if (since == null) {
			return;
		}
		databaseUnavailableSince = null;
		send(new Notification("[Sentinel] DB 연결 복구 - 스케줄 실행 재개",
				List.of("중단 기간: " + format(since) + " ~ " + format(now) + " (" + formatDuration(Duration.between(since, now))
						+ ")", "중단 기간 중 놓친 스케줄은 1회씩 실행됩니다")));
	}

	private void notifyRunResult(long runId) {
		try {
			runResultNotifier.notifyResult(runId);
		}
		catch (RuntimeException ex) {
			log.error("Failed to send result notification for run {}", runId, ex);
		}
	}

	private void send(Notification notification) {
		try {
			notifier.send(notification);
		}
		catch (RuntimeException ex) {
			log.error("Failed to send notification: {}", notification.title(), ex);
		}
	}

	private String format(Instant instant) {
		return TIME.format(instant.atZone(properties.zone())) + " (" + properties.zone().getId() + ")";
	}

	static String formatDuration(Duration duration) {
		return NotificationFormat.duration(duration);
	}

	private record Trigger(long suiteId, long environmentId) {
	}

}
