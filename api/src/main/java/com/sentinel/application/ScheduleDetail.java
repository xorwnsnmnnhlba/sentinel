package com.sentinel.application;

import java.time.Instant;

import com.sentinel.domain.TestSchedule;

public record ScheduleDetail(long id, long suiteId, long environmentId, String cron, String zone, boolean enabled,
		boolean skipHolidays, Instant nextRunAt, Instant lastTriggeredAt) {

	static ScheduleDetail of(TestSchedule schedule, String zone) {
		return new ScheduleDetail(schedule.getId(), schedule.getSuite().getId(), schedule.getEnvironment().getId(),
				schedule.getCron(), zone, schedule.isEnabled(), schedule.isSkipHolidays(), schedule.getNextRunAt(),
				schedule.getLastTriggeredAt());
	}

}
