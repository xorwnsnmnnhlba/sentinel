package com.sentinel.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import com.sentinel.application.ChangeLogService.Target;
import com.sentinel.domain.ChangeLog.Action;
import com.sentinel.domain.ChangeLog.TargetType;
import com.sentinel.domain.Holiday;
import com.sentinel.infra.persistence.HolidayRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 휴일 등록·삭제·조회. 등록한 날짜에는 "휴일 건너뛰기"를 켠 스케줄 실행을 건너뜀 ({@link SchedulePoller}).
 * 등록·삭제는 같은 트랜잭션에서 변경 이력을 남김 (누가 감시를 멈췄는지 확인용)
 */
@Service
@RequiredArgsConstructor
public class HolidayService {

	private final HolidayRepository holidays;

	private final ChangeLogService changeLog;

	private final ScheduleProperties scheduleProperties;

	private final Clock clock;

	/**
	 * @throws InvalidRequestException 이름 없음·100자 초과
	 * @throws ConflictException 이미 등록한 날짜
	 */
	@Transactional
	public HolidayItem create(LocalDate date, String name, long actorId) {
		if (holidays.existsByDateAndArchivedAtIsNull(date)) {
			throw new ConflictException("holiday " + date + " already exists");
		}
		Holiday holiday;
		try {
			holiday = holidays.save(new Holiday(date, name, actorId));
		}
		catch (IllegalArgumentException ex) {
			throw new InvalidRequestException(ex.getMessage());
		}
		changeLog.record(actorId, target(holiday), Action.CREATED);
		return HolidayItem.of(holiday);
	}

	/**
	 * 삭제(보관). 그날도 스케줄을 실행
	 * @throws NotFoundException 미존재 또는 이미 삭제
	 */
	@Transactional
	public void delete(long holidayId, long actorId) {
		Holiday holiday = holidays.findByIdAndArchivedAtIsNull(holidayId)
			.orElseThrow(() -> new NotFoundException("holiday", holidayId));
		holiday.archive(clock.instant());
		changeLog.record(actorId, target(holiday), Action.DELETED);
	}

	/**
	 * @param year 생략 시 올해 ({@code sentinel.schedule.zone} 기준)
	 * @return 그해 휴일, 날짜 순
	 */
	@Transactional(readOnly = true)
	public List<HolidayItem> list(Integer year) {
		int target = year != null ? year : LocalDate.ofInstant(clock.instant(), scheduleProperties.zone()).getYear();
		return holidays
			.findByArchivedAtIsNullAndDateBetweenOrderByDate(LocalDate.of(target, 1, 1), LocalDate.of(target, 12, 31))
			.stream()
			.map(HolidayItem::of)
			.toList();
	}

	/** 이름은 "날짜 이름" (예: 2026-10-09 한글날) */
	private static Target target(Holiday holiday) {
		return new Target(TargetType.HOLIDAY, holiday.getId(), holiday.getDate() + " " + holiday.getName(), null);
	}

	public record HolidayItem(long id, LocalDate date, String name) {

		static HolidayItem of(Holiday holiday) {
			return new HolidayItem(holiday.getId(), holiday.getDate(), holiday.getName());
		}

	}

}
