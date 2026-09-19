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
		TestSuite suite = suites.findByIdAndArchivedAtIsNull(suiteId).orElseThrow(() -> new NotFoundException("test suite", suiteId));
		Environment environment = environments.findByIdAndArchivedAtIsNull(environmentId)
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
	 * 부분 수정. null 필드는 변경하지 않음. cron 변경 후 활성 여부 적용
	 * @param enabled true면 현재 시각 기준 다음 실행 시각 재계산, false면 다음 실행 시각 제거
	 * @param cron 활성 상태면 현재 시각 기준 다음 실행 시각 재계산
	 * @throws InvalidRequestException 잘못된 cron
	 */
	@Transactional
	public ScheduleDetail update(long scheduleId, Boolean enabled, String cron) {
		TestSchedule schedule = find(scheduleId);
		if (cron != null) {
			try {
				schedule.changeCron(cron, clock.instant(), properties.zone());
			}
			catch (IllegalArgumentException ex) {
				throw new InvalidRequestException(ex.getMessage());
			}
		}
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
		return (suiteId == null ? schedules.findByArchivedAtIsNullOrderById() : schedules.findBySuiteIdAndArchivedAtIsNullOrderById(suiteId))
			.stream()
			.map(this::detailOf)
			.toList();
	}

	/**
	 * 삭제(보관). 비활성화 후 목록·단건 조회에서 제외
	 * @throws NotFoundException 미존재 또는 이미 삭제
	 */
	@Transactional
	public void delete(long scheduleId) {
		find(scheduleId).archive(clock.instant());
	}

	@Transactional(readOnly = true)
	public ScheduleDetail get(long scheduleId) {
		return detailOf(find(scheduleId));
	}

	private TestSchedule find(long scheduleId) {
		return schedules.findByIdAndArchivedAtIsNull(scheduleId).orElseThrow(() -> new NotFoundException("test schedule", scheduleId));
	}

	private ScheduleDetail detailOf(TestSchedule schedule) {
		return ScheduleDetail.of(schedule, properties.zone().getId());
	}

}
