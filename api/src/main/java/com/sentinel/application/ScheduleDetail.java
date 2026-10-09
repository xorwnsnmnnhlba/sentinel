package com.sentinel.application;

import java.time.Instant;

import com.sentinel.domain.TestSchedule;

/**
 * @param project 스위트의 프로젝트
 */
public record ScheduleDetail(long id, IdName project, IdName suite, IdName environment, String cron, String zone,
		boolean enabled, boolean skipHolidays, Instant nextRunAt, Instant lastTriggeredAt) {

	static ScheduleDetail of(TestSchedule schedule, String zone) {
		return new ScheduleDetail(schedule.getId(), IdName.of(schedule.getSuite().getProject()),
				IdName.of(schedule.getSuite()), IdName.of(schedule.getEnvironment()), schedule.getCron(), zone,
				schedule.isEnabled(), schedule.isSkipHolidays(), schedule.getNextRunAt(), schedule.getLastTriggeredAt());
	}

}
