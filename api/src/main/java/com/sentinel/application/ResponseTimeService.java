package com.sentinel.application;

import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.sentinel.domain.BaseEntity;
import com.sentinel.domain.Environment;
import com.sentinel.domain.TestCase;
import com.sentinel.domain.TestSuite;
import com.sentinel.infra.persistence.EnvironmentRepository;
import com.sentinel.infra.persistence.TestCaseRepository;
import com.sentinel.infra.persistence.TestResultRepository;
import com.sentinel.infra.persistence.TestSuiteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 응답시간 추세. 저장된 실행 결과에서 바로 집계 (별도 집계 테이블 없음).
 * <p>
 * 응답을 받은 결과(4xx·5xx·판정 실패 포함)만 대상이며 타임아웃·연결 실패는 제외. 환경마다 응답시간이 다르므로 환경을 지정해야 함.
 * 기간은 {@code sentinel.schedule.zone} 기준 날짜이고 종료일 당일 포함, 최대 {@link #MAX_DAYS}일
 */
@Service
@RequiredArgsConstructor
public class ResponseTimeService {

	static final int MAX_DAYS = 90;

	/** 기간 생략 시 추세는 최근 30일 */
	static final int DEFAULT_TREND_DAYS = 30;

	/** 기간 생략 시 스위트 요약은 최근 7일 */
	static final int DEFAULT_SUMMARY_DAYS = 7;

	private final TestCaseRepository testCases;

	private final TestSuiteRepository suites;

	private final EnvironmentRepository environments;

	private final TestResultRepository results;

	private final ScheduleProperties scheduleProperties;

	private final Clock clock;

	/**
	 * 케이스 1건의 일별 응답시간. 기간의 모든 날짜를 포함하며 결과가 없는 날은 건수 0, 값 null
	 * @throws NotFoundException 케이스·환경 미존재
	 * @throws InvalidRequestException 다른 프로젝트의 환경, 잘못된 기간
	 */
	@Transactional(readOnly = true)
	public Trend trend(long caseId, long environmentId, LocalDate from, LocalDate to) {
		TestCase testCase = testCases.findByIdAndArchivedAtIsNull(caseId)
			.orElseThrow(() -> new NotFoundException("test case", caseId));
		requireSameProject(testCase.getSuite(), environmentId);
		Period period = period(from, to, DEFAULT_TREND_DAYS);

		Map<LocalDate, Stats> byDay = new HashMap<>();
		for (Object[] row : results.dailyResponseTimes(caseId, environmentId, zone().getId(),
				period.startInstant(zone()), period.endInstant(zone()))) {
			byDay.put(date(row[0]), Stats.of(row, 1));
		}
		List<DailyPoint> points = new ArrayList<>();
		for (LocalDate day = period.from(); !day.isAfter(period.to()); day = day.plusDays(1)) {
			points.add(DailyPoint.of(day, byDay.getOrDefault(day, Stats.NONE)));
		}
		return new Trend(caseId, environmentId, period.from(), period.to(), points);
	}

	/**
	 * 스위트의 케이스별 응답시간. 스위트의 모든 케이스를 등록 순으로 포함하며 결과가 없는 케이스는 건수 0, 값 null
	 * @throws NotFoundException 스위트·환경 미존재
	 * @throws InvalidRequestException 다른 프로젝트의 환경, 잘못된 기간
	 */
	@Transactional(readOnly = true)
	public Summary summary(long suiteId, long environmentId, LocalDate from, LocalDate to) {
		TestSuite suite = suites.findByIdAndArchivedAtIsNull(suiteId)
			.orElseThrow(() -> new NotFoundException("test suite", suiteId));
		requireSameProject(suite, environmentId);
		Period period = period(from, to, DEFAULT_SUMMARY_DAYS);

		Map<Long, Stats> byCase = new HashMap<>();
		for (Object[] row : results.caseResponseTimes(suiteId, environmentId, period.startInstant(zone()),
				period.endInstant(zone()))) {
			byCase.put(((Number) row[0]).longValue(), Stats.of(row, 1));
		}
		List<CaseStats> cases = testCases.findBySuiteIdAndArchivedAtIsNullOrderById(suiteId)
			.stream()
			.map(testCase -> CaseStats.of(testCase, byCase.getOrDefault(testCase.getId(), Stats.NONE)))
			.toList();
		return new Summary(suiteId, environmentId, period.from(), period.to(), cases);
	}

	private void requireSameProject(TestSuite suite, long environmentId) {
		Environment environment = environments.findByIdAndArchivedAtIsNull(environmentId)
			.orElseThrow(() -> new NotFoundException("environment", environmentId));
		if (!BaseEntity.same(environment.getProject(), suite.getProject())) {
			throw new InvalidRequestException(
					"environment " + environmentId + " does not belong to the project of test suite " + suite.getId());
		}
	}

	private Period period(LocalDate from, LocalDate to, int defaultDays) {
		LocalDate end = to != null ? to : LocalDate.ofInstant(clock.instant(), zone());
		LocalDate start = from != null ? from : end.minusDays(defaultDays - 1L);
		if (start.isAfter(end)) {
			throw new InvalidRequestException("from must not be after to");
		}
		if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_DAYS) {
			throw new InvalidRequestException("period must be at most " + MAX_DAYS + " days");
		}
		return new Period(start, end);
	}

	private ZoneId zone() {
		return scheduleProperties.zone();
	}

	/** 드라이버에 따라 DATE가 java.sql.Date 또는 LocalDate로 옴 */
	private static LocalDate date(Object value) {
		return value instanceof Date date ? date.toLocalDate() : (LocalDate) value;
	}

	private record Period(LocalDate from, LocalDate to) {

		Instant startInstant(ZoneId zone) {
			return from.atStartOfDay(zone).toInstant();
		}

		Instant endInstant(ZoneId zone) {
			return to.plusDays(1).atStartOfDay(zone).toInstant();
		}

	}

	/** 집계 한 행. 백분위는 보간값이라 ms 단위로 반올림 */
	private record Stats(long samples, Long p50, Long p95, Long max) {

		static final Stats NONE = new Stats(0, null, null, null);

		static Stats of(Object[] row, int offset) {
			return new Stats(((Number) row[offset]).longValue(), round(row[offset + 1]), round(row[offset + 2]),
					round(row[offset + 3]));
		}

		private static Long round(Object value) {
			return value == null ? null : Math.round(((Number) value).doubleValue());
		}

	}

	/**
	 * @param samples 응답을 받은 결과 수. 0이면 값은 null
	 * @param p50 중앙값(ms)
	 * @param p95 95백분위(ms)
	 */
	public record DailyPoint(LocalDate date, long samples, Long p50, Long p95, Long max) {

		static DailyPoint of(LocalDate date, Stats stats) {
			return new DailyPoint(date, stats.samples(), stats.p50(), stats.p95(), stats.max());
		}

	}

	public record Trend(long caseId, long environmentId, LocalDate from, LocalDate to, List<DailyPoint> points) {
	}

	public record CaseStats(long caseId, String name, long samples, Long p50, Long p95, Long max) {

		static CaseStats of(TestCase testCase, Stats stats) {
			return new CaseStats(testCase.getId(), testCase.getName(), stats.samples(), stats.p50(), stats.p95(),
					stats.max());
		}

	}

	public record Summary(long suiteId, long environmentId, LocalDate from, LocalDate to, List<CaseStats> cases) {
	}

}
