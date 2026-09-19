package com.sentinel.application;

import java.time.Clock;
import java.util.List;

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

	/**
	 * 부분 수정. null 필드는 변경하지 않음
	 * @param enabled true면 현재 시각 기준 다음 실행 시각 재계산, false면 다음 실행 시각 제거
	 */
	@Transactional
	public ScheduleDetail update(long scheduleId, Boolean enabled) {
		TestSchedule schedule = find(scheduleId);
		if (Boolean.TRUE.equals(enabled)) {
			schedule.enable(clock.instant(), properties.zone());
		}
		else if (Boolean.FALSE.equals(enabled)) {
			schedule.disable();
		}
		return detailOf(schedule);
	}

	/**
	 * @param suiteId null이면 전체
	 */
	@Transactional(readOnly = true)
	public List<ScheduleDetail> list(Long suiteId) {
		return (suiteId == null ? schedules.findAllByOrderById() : schedules.findBySuiteIdOrderById(suiteId))
			.stream()
			.map(this::detailOf)
			.toList();
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
