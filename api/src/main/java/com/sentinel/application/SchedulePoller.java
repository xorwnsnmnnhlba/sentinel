package com.sentinel.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.sentinel.domain.TestRun;
import com.sentinel.domain.TestSchedule;
import com.sentinel.infra.persistence.TestScheduleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * DB 폴링 스케줄러. 실행 시각에 도달한 스케줄을 찾아 다음 실행 시각을 먼저 갱신한 뒤 스위트 실행.
 * <p>
 * DB 접속 실패(VPN 끊김 등)는 즉시 재시도해도 복구되지 않으므로 해당 주기를 건너뛰고 다음 주기 대기.
 */
@Component
public class SchedulePoller {

	private static final Logger log = LoggerFactory.getLogger(SchedulePoller.class);

	private final TestScheduleRepository schedules;

	private final TestRunService testRunService;

	private final ScheduleProperties properties;

	private final Clock clock;

	private final TransactionTemplate transaction;

	SchedulePoller(TestScheduleRepository schedules, TestRunService testRunService, ScheduleProperties properties,
			Clock clock, PlatformTransactionManager transactionManager) {
		this.schedules = schedules;
		this.testRunService = testRunService;
		this.properties = properties;
		this.clock = clock;
		this.transaction = new TransactionTemplate(transactionManager);
	}

	/**
	 * @return 이번 주기에 생성된 실행 ID
	 */
	public List<Long> poll() {
		Instant now = clock.instant();
		List<Long> dueIds;
		try {
			dueIds = transaction.execute(status -> schedules
				.findByEnabledTrueAndNextRunAtLessThanEqualOrderByNextRunAt(now)
				.stream()
				.map(TestSchedule::getId)
				.toList());
		}
		catch (DataAccessException | TransactionException ex) {
			log.warn("Skipping schedule poll at {}: database unavailable ({})", now, ex.getMessage());
			return List.of();
		}

		List<Long> runIds = new ArrayList<>();
		for (Long scheduleId : dueIds) {
			try {
				Trigger trigger = transaction.execute(status -> claim(scheduleId, now));
				if (trigger != null) {
					runIds.add(testRunService.runSuite(trigger.suiteId(), trigger.environmentId(),
							TestRun.Trigger.SCHEDULED));
				}
			}
			catch (RuntimeException ex) {
				log.error("Scheduled run failed for schedule {}", scheduleId, ex);
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

	private record Trigger(long suiteId, long environmentId) {
	}

}
