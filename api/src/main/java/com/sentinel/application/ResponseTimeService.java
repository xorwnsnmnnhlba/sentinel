package com.sentinel.application;

import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
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
 * 기간은 {@code sentinel.schedule.zone} 기준 날짜이고 종료일 당일 포함, 최대 {@link DatePeriod#MAX_DAYS}일
 */
@Service
@RequiredArgsConstructor
public class ResponseTimeService {

	/** 기간 생략 시 추세는 최근 30일 */
	static final int DEFAULT_TREND_DAYS = 30;

	/** 기간 생략 시 스위트 요약은 최근 7일 */
	static final int DEFAULT_SUMMARY_DAYS = 7;

	/** 기간 생략 시 변화 비교는 최근 7일과 그 앞 7일 */
	static final int DEFAULT_CHANGE_DAYS = 7;

	/** 변화 비교에 필요한 기간별 최소 결과 수. 한두 건으로 계산한 p95는 흔들림이 커서 제외 */
	static final int MIN_CHANGE_SAMPLES = 3;

	static final int DEFAULT_CHANGE_LIMIT = 5;

	static final int MAX_CHANGE_LIMIT = 50;

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
		DatePeriod period = period(from, to, DEFAULT_TREND_DAYS);

		Map<LocalDate, Stats> byDay = new HashMap<>();
		for (Object[] row : results.dailyResponseTimes(caseId, environmentId, zone().getId(),
				period.start(), period.end())) {
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
		DatePeriod period = period(from, to, DEFAULT_SUMMARY_DAYS);

		Map<Long, Stats> byCase = new HashMap<>();
		for (Object[] row : results.caseResponseTimes(suiteId, environmentId, period.start(),
				period.end())) {
			byCase.put(((Number) row[0]).longValue(), Stats.of(row, 1));
		}
		List<CaseStats> cases = testCases.findBySuiteIdAndArchivedAtIsNullOrderById(suiteId)
			.stream()
			.map(testCase -> CaseStats.of(testCase, byCase.getOrDefault(testCase.getId(), Stats.NONE)))
			.toList();
		return new Summary(suiteId, environmentId, period.from(), period.to(), cases);
	}

	/**
	 * 케이스·환경별 p95를 바로 앞의 같은 길이 기간과 비교. 증가율이 큰 순서(가장 나빠진 순)로 최대 limit건.
	 * 두 기간 모두 결과가 {@link #MIN_CHANGE_SAMPLES}건 이상이고 이전 p95가 0보다 큰 경우만 대상이며, 보관된 케이스·환경은 제외
	 * @throws InvalidRequestException 잘못된 기간, limit 범위 초과
	 */
	@Transactional(readOnly = true)
	public Changes changes(LocalDate from, LocalDate to, int limit) {
		if (limit < 1 || limit > MAX_CHANGE_LIMIT) {
			throw new InvalidRequestException("limit must be between 1 and " + MAX_CHANGE_LIMIT);
		}
		DatePeriod current = period(from, to, DEFAULT_CHANGE_DAYS);
		DatePeriod previous = current.previous();

		// [이전 기간, 현재 기간]
		Map<CaseEnvironment, Stats[]> windows = new HashMap<>();
		for (Object[] row : results.p95ByWindow(previous.start(), current.start(), current.end())) {
			CaseEnvironment key = new CaseEnvironment(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
			int window = ((Number) row[2]).intValue();
			windows.computeIfAbsent(key, k -> new Stats[2])[window] = new Stats(((Number) row[3]).longValue(),
					null, Stats.round(row[4]), null);
		}
		windows.values().removeIf(pair -> pair[0] == null || pair[1] == null
				|| pair[0].samples() < MIN_CHANGE_SAMPLES || pair[1].samples() < MIN_CHANGE_SAMPLES
				|| pair[0].p95() == 0);

		Map<Long, TestCase> cases = new HashMap<>();
		Map<Long, Environment> environmentsById = new HashMap<>();
		if (!windows.isEmpty()) {
			testCases.findActiveWithSuiteByIdIn(windows.keySet().stream().map(CaseEnvironment::caseId).distinct().toList())
				.forEach(testCase -> cases.put(testCase.getId(), testCase));
			environments.findAllById(windows.keySet().stream().map(CaseEnvironment::environmentId).distinct().toList())
				.stream()
				.filter(environment -> !environment.isArchived())
				.forEach(environment -> environmentsById.put(environment.getId(), environment));
		}
		List<Change> changes = windows.entrySet()
			.stream()
			.filter(entry -> cases.containsKey(entry.getKey().caseId())
					&& environmentsById.containsKey(entry.getKey().environmentId()))
			.map(entry -> Change.of(cases.get(entry.getKey().caseId()),
					environmentsById.get(entry.getKey().environmentId()), entry.getValue()[0], entry.getValue()[1]))
			.sorted(Comparator.comparingDouble(Change::change).reversed().thenComparing(Change::currentP95,
					Comparator.reverseOrder()))
			.limit(limit)
			.toList();
		return new Changes(current.from(), current.to(), previous.from(), previous.to(), changes);
	}

	private void requireSameProject(TestSuite suite, long environmentId) {
		Environment environment = environments.findByIdAndArchivedAtIsNull(environmentId)
			.orElseThrow(() -> new NotFoundException("environment", environmentId));
		if (!BaseEntity.same(environment.getProject(), suite.getProject())) {
			throw new InvalidRequestException(
					"environment " + environmentId + " does not belong to the project of test suite " + suite.getId());
		}
	}

	private DatePeriod period(LocalDate from, LocalDate to, int defaultDays) {
		return DatePeriod.of(from, to, defaultDays, clock, zone());
	}

	private ZoneId zone() {
		return scheduleProperties.zone();
	}

	/** 드라이버에 따라 DATE가 java.sql.Date 또는 LocalDate로 옴 */
	private static LocalDate date(Object value) {
		return value instanceof Date date ? date.toLocalDate() : (LocalDate) value;
	}

	/** 변화 비교 단위. 환경마다 응답시간이 달라 케이스·환경 쌍으로 비교 */
	private record CaseEnvironment(long caseId, long environmentId) {
	}

	/** 집계 한 행. 백분위는 보간값이라 ms 단위로 반올림 */
	private record Stats(long samples, Long p50, Long p95, Long max) {

		static final Stats NONE = new Stats(0, null, null, null);

		static Stats of(Object[] row, int offset) {
			return new Stats(((Number) row[offset]).longValue(), round(row[offset + 1]), round(row[offset + 2]),
					round(row[offset + 3]));
		}

		static Long round(Object value) {
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

	/**
	 * @param previousFrom 비교 대상인 바로 앞 기간의 시작일
	 * @param changes 증가율이 큰 순서
	 */
	public record Changes(LocalDate from, LocalDate to, LocalDate previousFrom, LocalDate previousTo,
			List<Change> changes) {
	}

	/**
	 * @param change 이전 대비 p95 증가율. 0.42는 42% 느려짐, 음수는 빨라짐
	 */
	public record Change(IdName testCase, IdName suite, IdName environment, long previousP95, long currentP95,
			double change, long previousSamples, long currentSamples) {

		static Change of(TestCase testCase, Environment environment, Stats previous, Stats current) {
			double change = (double) (current.p95() - previous.p95()) / previous.p95();
			return new Change(new IdName(testCase.getId(), testCase.getName()),
					new IdName(testCase.getSuite().getId(), testCase.getSuite().getName()),
					new IdName(environment.getId(), environment.getName()), previous.p95(), current.p95(), change,
					previous.samples(), current.samples());
		}

	}

}
