package com.sentinel.application;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

import com.sentinel.application.ChangeLogService.Changes;
import com.sentinel.application.ChangeLogService.Target;
import com.sentinel.domain.ChangeLog.Action;
import com.sentinel.domain.ChangeLog.TargetType;
import com.sentinel.domain.Environment;
import com.sentinel.domain.TestSchedule;
import com.sentinel.domain.TestSuite;
import com.sentinel.infra.persistence.EnvironmentRepository;
import com.sentinel.infra.persistence.ProjectRepository;
import com.sentinel.infra.persistence.TestScheduleRepository;
import com.sentinel.infra.persistence.TestSuiteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 스케줄 등록·수정·삭제·조회. 등록·수정·삭제는 같은 트랜잭션에서 변경 이력을 남김 ({@code actorId}는 변경한 사용자).
 * 스케줄러가 다음 실행 시각을 옮기는 것은 설정 변경이 아니므로 기록하지 않음
 */
@Service
@RequiredArgsConstructor
public class ScheduleService {

	/** 한 번에 바꿀 수 있는 스케줄 수 */
	public static final int MAX_BULK_UPDATE = 200;

	private final TestScheduleRepository schedules;

	private final TestSuiteRepository suites;

	private final EnvironmentRepository environments;

	private final ProjectRepository projects;

	private final ScheduleProperties properties;

	private final Clock clock;

	private final ChangeLogService changeLog;

	/**
	 * @throws NotFoundException 스위트 또는 환경 미존재
	 * @throws InvalidRequestException 잘못된 cron, 스위트와 환경의 프로젝트 불일치
	 */
	@Transactional
	public ScheduleDetail create(long suiteId, long environmentId, String cron, long actorId) {
		return create(suiteId, environmentId, cron, false, actorId);
	}

	/**
	 * @param skipHolidays 휴일에는 실행하지 않음
	 * @throws NotFoundException 스위트 또는 환경 미존재
	 * @throws InvalidRequestException 잘못된 cron, 스위트와 환경의 프로젝트 불일치
	 */
	@Transactional
	public ScheduleDetail create(long suiteId, long environmentId, String cron, boolean skipHolidays, long actorId) {
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
		schedule.changeSkipHolidays(skipHolidays);
		schedules.save(schedule);
		changeLog.record(actorId, target(schedule), Action.CREATED);
		return detailOf(schedule);
	}

	/**
	 * 부분 수정. null 필드는 변경하지 않음. 환경·cron 변경 후 활성 여부 적용
	 * @param enabled true면 현재 시각 기준 다음 실행 시각 재계산, false면 다음 실행 시각 제거
	 * @param cron 활성 상태면 현재 시각 기준 다음 실행 시각 재계산
	 * @param environmentId 스위트와 같은 프로젝트의 환경만 허용
	 * @throws NotFoundException 스케줄 또는 환경 미존재
	 * @throws InvalidRequestException 잘못된 cron, 스위트와 환경의 프로젝트 불일치
	 */
	@Transactional
	public ScheduleDetail update(long scheduleId, Boolean enabled, String cron, Long environmentId, long actorId) {
		return update(scheduleId, enabled, cron, environmentId, null, actorId);
	}

	/**
	 * 부분 수정. 위와 같고 휴일 건너뛰기 포함
	 * @param skipHolidays null이면 유지
	 */
	@Transactional
	public ScheduleDetail update(long scheduleId, Boolean enabled, String cron, Long environmentId, Boolean skipHolidays,
			long actorId) {
		TestSchedule schedule = find(scheduleId);
		ScheduleDetail before = detailOf(schedule);
		if (environmentId != null) {
			Environment environment = environments.findByIdAndArchivedAtIsNull(environmentId)
				.orElseThrow(() -> new NotFoundException("environment", environmentId));
			try {
				schedule.changeEnvironment(environment);
			}
			catch (IllegalArgumentException ex) {
				throw new InvalidRequestException(ex.getMessage());
			}
		}
		if (cron != null) {
			try {
				schedule.changeCron(cron, clock.instant(), properties.zone());
			}
			catch (IllegalArgumentException ex) {
				throw new InvalidRequestException(ex.getMessage());
			}
		}
		if (skipHolidays != null) {
			schedule.changeSkipHolidays(skipHolidays);
		}
		if (Boolean.TRUE.equals(enabled)) {
			schedule.enable(clock.instant(), properties.zone());
		}
		else if (Boolean.FALSE.equals(enabled)) {
			schedule.disable();
		}
		ScheduleDetail after = detailOf(schedule);
		changeLog.record(actorId, target(schedule), Action.UPDATED,
				new Changes().compare("environment", before.environment().id(), after.environment().id())
					.compare("cron", before.cron(), after.cron())
					.compare("enabled", before.enabled(), after.enabled())
					.compare("skipHolidays", before.skipHolidays(), after.skipHolidays())
					.fields());
		return after;
	}

	/**
	 * 여러 스케줄의 활성·휴일 건너뛰기를 한 번에 변경. 한 트랜잭션이라 하나라도 없으면(404) 전부 미변경. 스케줄마다 변경 이력
	 * <p>
	 * 이미 그 상태인 스케줄은 그대로 둠: 켜져 있는 스케줄을 다시 켜면 다음 실행 시각이 지금 기준으로 다시 계산되어, 곧 돌 실행을 건너뛸 수 있음
	 * @param enabled null이면 유지
	 * @param skipHolidays null이면 유지
	 * @return 요청 순서대로 (중복 ID는 한 번)
	 * @throws InvalidRequestException ID 없음·{@value #MAX_BULK_UPDATE}건 초과, 바꿀 항목 없음
	 * @throws NotFoundException 스케줄 미존재 또는 삭제
	 */
	@Transactional
	public List<ScheduleDetail> updateAll(List<Long> scheduleIds, Boolean enabled, Boolean skipHolidays, long actorId) {
		if (scheduleIds == null || scheduleIds.isEmpty() || scheduleIds.stream().anyMatch(Objects::isNull)) {
			throw new InvalidRequestException("scheduleIds must not be empty");
		}
		List<Long> ids = scheduleIds.stream().distinct().toList();
		if (ids.size() > MAX_BULK_UPDATE) {
			throw new InvalidRequestException("at most " + MAX_BULK_UPDATE + " schedules can be updated at once");
		}
		if (enabled == null && skipHolidays == null) {
			throw new InvalidRequestException("enabled or skipHolidays is required");
		}
		return ids.stream().map(id -> {
			Boolean changeEnabled = enabled != null && enabled == find(id).isEnabled() ? null : enabled;
			return update(id, changeEnabled, null, null, skipHolidays, actorId);
		}).toList();
	}

	/**
	 * @param suiteId null이면 전체
	 */
	@Transactional(readOnly = true)
	public List<ScheduleDetail> list(Long suiteId) {
		List<TestSchedule> found = suiteId == null ? schedules.findByArchivedAtIsNullOrderById()
				: schedules.findBySuiteIdAndArchivedAtIsNullOrderById(suiteId);
		Preload.of(suites, found.stream().map(schedule -> schedule.getSuite().getId()));
		Preload.of(environments, found.stream().map(schedule -> schedule.getEnvironment().getId()));
		Preload.of(projects, found.stream().map(schedule -> schedule.getSuite().getProject().getId()));
		return found.stream().map(this::detailOf).toList();
	}

	/**
	 * 삭제(보관). 비활성화 후 목록·단건 조회에서 제외
	 * @throws NotFoundException 미존재 또는 이미 삭제
	 */
	@Transactional
	public void delete(long scheduleId, long actorId) {
		TestSchedule schedule = find(scheduleId);
		schedule.archive(clock.instant());
		changeLog.record(actorId, target(schedule), Action.DELETED);
	}

	/** 스케줄은 이름이 없으므로 스위트 이름으로 표시 */
	private static Target target(TestSchedule schedule) {
		return new Target(TargetType.SCHEDULE, schedule.getId(), schedule.getSuite().getName(),
				schedule.getSuite().getProject().getId(), schedule.getSuite().getId());
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
