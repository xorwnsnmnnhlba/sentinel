package com.sentinel.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * 날짜 기간 (시작일·종료일 포함). 집계 API 공통 규칙: 지정한 시간대 기준, 생략 시 오늘까지 기본 일수, 최대 {@link #MAX_DAYS}일
 *
 * @param zone 날짜 해석 시간대. 경계는 시작일 00:00 ~ 종료일 다음 날 00:00 직전
 */
record DatePeriod(LocalDate from, LocalDate to, ZoneId zone) {

	public static final int MAX_DAYS = 90;

	/**
	 * @throws InvalidRequestException 시작일이 종료일보다 뒤, 기간 초과
	 */
	public static DatePeriod of(LocalDate from, LocalDate to, int defaultDays, Clock clock, ZoneId zone) {
		LocalDate end = to != null ? to : LocalDate.ofInstant(clock.instant(), zone);
		LocalDate start = from != null ? from : end.minusDays(defaultDays - 1L);
		if (start.isAfter(end)) {
			throw new InvalidRequestException("from must not be after to");
		}
		DatePeriod period = new DatePeriod(start, end, zone);
		if (period.days() > MAX_DAYS) {
			throw new InvalidRequestException("period must be at most " + MAX_DAYS + " days");
		}
		return period;
	}

	public long days() {
		return ChronoUnit.DAYS.between(from, to) + 1;
	}

	/** 바로 앞의 같은 길이 기간. 변화 비교용 */
	public DatePeriod previous() {
		return new DatePeriod(from.minusDays(days()), from.minusDays(1), zone);
	}

	public Instant start() {
		return from.atStartOfDay(zone).toInstant();
	}

	/** 종료일 다음 날 00:00 (미포함 상한) */
	public Instant end() {
		return to.plusDays(1).atStartOfDay(zone).toInstant();
	}

}
