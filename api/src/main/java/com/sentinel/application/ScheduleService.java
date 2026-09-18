package com.sentinel.application;

import java.time.Clock;

import com.sentinel.domain.Environment;
import com.sentinel.domain.TestSchedule;
import com.sentinel.domain.TestSuite;
import com.sentinel.infra.persistence.EnvironmentRepository;
import com.sentinel.infra.persistence.TestScheduleRepository;
import com.sentinel.infra.persistence.TestSuiteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ScheduleService {

	private final TestScheduleRepository schedules;

	private final TestSuiteRepository suites;

	private final EnvironmentRepository environments;

	private final ScheduleProperties properties;

	private final Clock clock;

	ScheduleService(TestScheduleRepository schedules, TestSuiteRepository suites, EnvironmentRepository environments,
			ScheduleProperties properties, Clock clock) {
		this.schedules = schedules;
		this.suites = suites;
		this.environments = environments;
		this.properties = properties;
		this.clock = clock;
	}

	/**
	 * @throws NotFoundException 스위트 또는 환경 미존재
	 * @throws InvalidRequestException 잘못된 cron, 스위트와 환경의 프로젝트 불일치
	 */
	@Transactional
	public ScheduleDetail create(long suiteId, long environmentId, String cron) {
		TestSuite suite = suites.findById(suiteId).orElseThrow(() -> new NotFoundException("test suite", suiteId));
		Environment environment = environments.findById(environmentId)
			.orElseThrow(() -> new NotFoundException("environment", environmentId));
		TestSchedule schedule;
		try {
			schedule = new TestSchedule(suite, environment, cron, clock.instant(), properties.zone());
		}
		catch (IllegalArgumentException ex) {
			throw new InvalidRequestException(ex.getMessage());
		}
		return detailOf(schedules.save(schedule));
	}

	@Transactional
	public ScheduleDetail enable(long scheduleId) {
		TestSchedule schedule = find(scheduleId);
		schedule.enable(clock.instant(), properties.zone());
		return detailOf(schedule);
	}

	@Transactional
	public ScheduleDetail disable(long scheduleId) {
		TestSchedule schedule = find(scheduleId);
		schedule.disable();
		return detailOf(schedule);
	}

	@Transactional(readOnly = true)
	public ScheduleDetail get(long scheduleId) {
		return detailOf(find(scheduleId));
	}

	private TestSchedule find(long scheduleId) {
		return schedules.findById(scheduleId).orElseThrow(() -> new NotFoundException("test schedule", scheduleId));
	}

	private ScheduleDetail detailOf(TestSchedule schedule) {
		return ScheduleDetail.of(schedule, properties.zone().getId());
	}

}
