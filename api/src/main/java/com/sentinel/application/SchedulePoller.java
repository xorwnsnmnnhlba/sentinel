package com.sentinel.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import com.sentinel.application.notification.Notification;
import com.sentinel.application.notification.NotificationFormat;
import com.sentinel.application.notification.Notifier;
import com.sentinel.application.notification.RunResultNotifier;
import com.sentinel.domain.MaintenanceWindow;
import com.sentinel.domain.TestRun;
import com.sentinel.domain.TestSchedule;
import com.sentinel.infra.persistence.MaintenanceWindowRepository;
import com.sentinel.infra.persistence.TestScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

/**
 * DB 폴링 스케줄러. 실행 시각에 도달한 스케줄을 찾아 다음 실행 시각을 먼저 갱신한 뒤 스위트 실행.
 * 환경이 점검 중이면 실행하지 않고 다음 실행 시각만 갱신 (실행 기록·알림 없음, {@link MaintenanceWindow}).
 * <p>
 * DB 접속 실패(VPN 끊김 등)는 즉시 재시도해도 복구되지 않으므로 해당 주기를 건너뛰고 다음 주기 대기.
 * 접속 실패는 최초 1회, 복구 시 1회 알림 발송. 스케줄 실행 결과에 실패가 있으면 실패 알림 발송.
 * 알림 발송 실패는 로그만 남기고 폴링 계속.
 * <p>
 * 예정 시각보다 기준 시간({@link ScheduleProperties#gapThreshold()}) 넘게 늦은 스케줄이 있으면 폴링이 멈췄던 기간을
 * 모니터링 공백으로 기록하고 알림 (Sentinel 중지·서버 절전 등은 재개 후에야 알 수 있음. 멈춘 즉시 알려면 {@link Heartbeat}). 앞 공백에 이어 붙인 경우와
 * DB 연결 복구 알림을 보낸 경우(같은 기간을 알림)는 공백 알림 생략. 기록 실패는 로그만 남기고 알림 발송
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

	private final MaintenanceWindowRepository maintenanceWindows;

	private final MonitoringGapService monitoringGaps;

	private final Heartbeat heartbeat;

	/** DB 접속 실패 시작 시각. 정상 시 null. 스케줄러 단일 스레드에서만 갱신 */
	private volatile Instant databaseUnavailableSince;

	/**
	 * @return 이번 주기에 생성된 실행 ID
	 */
	public List<Long> poll() {
		Instant now = clock.instant();
		List<Due> due;
		try {
			due = transactions.write(status -> schedules
				.findByEnabledTrueAndNextRunAtLessThanEqualOrderByNextRunAt(now)
				.stream()
				.map(schedule -> new Due(schedule.getId(), schedule.getNextRunAt()))
				.toList());
		}
		catch (DataAccessException | TransactionException ex) {
			String reason = ex.getMostSpecificCause().getMessage();
			log.warn("Skipping schedule poll at {}: database unavailable ({})", now, reason);
			onDatabaseUnavailable(now, reason);
			return List.of();
		}
		boolean recovered = onDatabaseAvailable(now);
		// 실행 전에 보냄. 스위트 실행이 길어도 다음 신호가 실행 시간만큼만 늦어짐
		beat();
		checkGap(due, now, recovered);

		List<Long> runIds = new ArrayList<>();
		for (Long scheduleId : due.stream().map(Due::scheduleId).toList()) {
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

	/** 실행할 스케줄의 다음 실행 시각을 옮기고 실행 대상 반환. 환경이 점검 중이면 건너뛰고 null */
	private Trigger claim(long scheduleId, Instant now) {
		TestSchedule schedule = schedules.findById(scheduleId).orElse(null);
		if (schedule == null || !schedule.isDue(now)) {
			return null;
		}
		Optional<MaintenanceWindow> maintenance = maintenanceWindows.findActive(schedule.getEnvironment().getId(), now);
		if (maintenance.isPresent()) {
			schedule.skip(now, properties.zone());
			log.info("Skipped schedule {} (suite {}) at {}: environment {} is under maintenance until {}", scheduleId,
					schedule.getSuite().getId(), now, schedule.getEnvironment().getId(), maintenance.get().getEndsAt());
			return null;
		}
		schedule.markTriggered(now, properties.zone());
		return new Trigger(schedule.getSuite().getId(), schedule.getEnvironment().getId());
	}

	/** 예정 시각보다 기준 시간 넘게 늦은 스케줄이 있으면 공백 기록·알림 */
	private void checkGap(List<Due> due, Instant now, boolean recovered) {
		Instant onTime = now.minus(properties.gapThreshold());
		List<Instant> late = due.stream().map(Due::nextRunAt).filter(nextRunAt -> nextRunAt.isBefore(onTime)).toList();
		if (late.isEmpty()) {
			return;
		}
		Instant startedAt = Collections.min(late);
		boolean started;
		try {
			started = monitoringGaps.record(startedAt, now, late.size());
		}
		catch (RuntimeException ex) {
			log.error("Failed to record monitoring gap from {} to {}", startedAt, now, ex);
			started = true;
		}
		log.warn("Monitoring gap from {} to {}: {} schedules ran late", startedAt, now, late.size());
		if (!started || recovered) {
			return;
		}
		send(new Notification("[Sentinel] 모니터링 공백 - 스케줄이 제때 실행되지 않음",
				List.of("기간: " + format(startedAt) + " ~ " + format(now) + " ("
						+ formatDuration(Duration.between(startedAt, now)) + ")", "제때 실행되지 않은 스케줄: " + late.size() + "건",
						"놓친 실행은 스케줄마다 1회씩 지금 실행합니다", "원인 예: Sentinel 중지, 서버 절전, 오래 걸린 앞 실행")));
	}

	private void onDatabaseUnavailable(Instant now, String reason) {
		if (databaseUnavailableSince != null) {
			return;
		}
		databaseUnavailableSince = now;
		send(new Notification("[Sentinel] DB 연결 실패 - 스케줄 실행 중단",
				List.of("발생 시각: " + format(now), "사유: " + reason, "복구될 때까지 스케줄 실행을 건너뜁니다")));
	}

	/** @return 접속 실패에서 복구됨 (복구 알림 발송) */
	private boolean onDatabaseAvailable(Instant now) {
		Instant since = databaseUnavailableSince;
		if (since == null) {
			return false;
		}
		databaseUnavailableSince = null;
		send(new Notification("[Sentinel] DB 연결 복구 - 스케줄 실행 재개",
				List.of("중단 기간: " + format(since) + " ~ " + format(now) + " (" + formatDuration(Duration.between(since, now))
						+ ")", "중단 기간 중 놓친 스케줄은 1회씩 실행됩니다")));
		return true;
	}

	private void notifyRunResult(long runId) {
		try {
			runResultNotifier.notifyResult(runId);
		}
		catch (RuntimeException ex) {
			log.error("Failed to send result notification for run {}", runId, ex);
		}
	}

	private void beat() {
		try {
			heartbeat.beat();
		}
		catch (RuntimeException ex) {
			log.error("Heartbeat failed unexpectedly", ex);
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

	private record Due(long scheduleId, Instant nextRunAt) {
	}

	private record Trigger(long suiteId, long environmentId) {
	}

}
