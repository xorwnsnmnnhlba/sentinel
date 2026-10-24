package com.sentinel.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

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

	/** 한 번에 등록할 수 있는 휴일 수. 1년치 공휴일은 20건 안팎 */
	public static final int MAX_IMPORT = 100;

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
			throw InvalidRequestException.of(ex);
		}
		changeLog.record(actorId, target(holiday), Action.CREATED);
		return HolidayItem.of(holiday);
	}

	/**
	 * 여러 휴일을 한 트랜잭션으로 등록 (달력·문서에서 붙여 넣은 목록). 이미 등록한 날짜는 건너뜀 (같은 목록을 다시 붙여 넣어도 안전)
	 * 한 건이라도 잘못되면 전부 미등록. 등록한 휴일마다 변경 이력
	 * @throws InvalidRequestException 빈 목록·{@value #MAX_IMPORT}건 초과, 날짜 없음, 목록 안 같은 날짜, 이름 없음·100자 초과.
	 * 메시지에 위치 표시 (예: {@code holidays[2] 2027-02-07: name must not be blank})
	 */
	@Transactional
	public HolidayImport importHolidays(List<NewHoliday> requested, long actorId) {
		if (requested == null || requested.isEmpty()) {
			throw new InvalidRequestException("holidays must not be empty");
		}
		if (requested.size() > MAX_IMPORT) {
			throw new InvalidRequestException("at most " + MAX_IMPORT + " holidays can be imported at once");
		}
		// 이름은 건너뛸 날짜도 확인 (잘못된 줄이 이미 등록된 날짜라는 이유로 조용히 통과하지 않도록)
		Set<LocalDate> seen = new HashSet<>();
		List<Holiday> candidates = new ArrayList<>();
		for (int i = 0; i < requested.size(); i++) {
			NewHoliday holiday = requested.get(i);
			if (holiday == null || holiday.date() == null) {
				throw new InvalidRequestException("holidays[" + i + "]: date is required");
			}
			if (!seen.add(holiday.date())) {
				throw new InvalidRequestException("holidays[" + i + "] " + holiday.date() + ": appears more than once");
			}
			try {
				candidates.add(new Holiday(holiday.date(), holiday.name(), actorId));
			}
			catch (IllegalArgumentException ex) {
				throw new InvalidRequestException("holidays[" + i + "] " + holiday.date() + ": " + ex.getMessage());
			}
		}
		Map<LocalDate, Holiday> existing = holidays.findByArchivedAtIsNullAndDateIn(seen)
			.stream()
			.collect(Collectors.toMap(Holiday::getDate, Function.identity()));
		List<HolidayItem> created = new ArrayList<>();
		List<HolidayItem> skipped = new ArrayList<>();
		for (Holiday candidate : candidates) {
			Holiday registered = existing.get(candidate.getDate());
			if (registered != null) {
				skipped.add(HolidayItem.of(registered));
				continue;
			}
			Holiday saved = holidays.save(candidate);
			changeLog.record(actorId, target(saved), Action.CREATED);
			created.add(HolidayItem.of(saved));
		}
		return new HolidayImport(created, skipped);
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
	 * @throws NotFoundException 미존재 또는 삭제
	 */
	@Transactional(readOnly = true)
	public HolidayItem get(long holidayId) {
		return holidays.findByIdAndArchivedAtIsNull(holidayId)
			.map(HolidayItem::of)
			.orElseThrow(() -> new NotFoundException("holiday", holidayId));
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

	/** 등록 요청 1건 */
	public record NewHoliday(LocalDate date, String name) {
	}

	/**
	 * @param created 새로 등록한 휴일, 요청 순
	 * @param skipped 이미 등록되어 건너뛴 날짜의 기존 휴일 (이름은 기존 이름)
	 */
	public record HolidayImport(List<HolidayItem> created, List<HolidayItem> skipped) {
	}

	public record HolidayItem(long id, LocalDate date, String name) {

		static HolidayItem of(Holiday holiday) {
			return new HolidayItem(holiday.getId(), holiday.getDate(), holiday.getName());
		}

	}

}
