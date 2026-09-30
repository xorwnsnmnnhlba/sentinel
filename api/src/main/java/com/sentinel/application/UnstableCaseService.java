package com.sentinel.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.sentinel.domain.Environment;
import com.sentinel.domain.TestCase;
import com.sentinel.infra.persistence.EnvironmentRepository;
import com.sentinel.infra.persistence.TestCaseRepository;
import com.sentinel.infra.persistence.TestResultRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 불안정 케이스. 기간 안 스케줄 실행에서 실패했다 통과했다를 반복하거나 응답이 없어 다시 보낸 일이 잦은 케이스·환경.
 * <p>
 * 한 번의 장애(실패가 이어지다 복구)는 실패 구간 1이라 제외하고, 실패 구간이 {@link #MIN_FAILURE_EPISODES}회 이상이거나
 * 재시도한 실행이 {@link #MIN_RETRIED_RUNS}회 이상이면 불안정 (재시도로 통과해 가려진 흔들림 포함).
 * 수동 실행은 수정 확인용이 많아 제외. 삭제된 케이스·환경은 제외
 */
@Service
@RequiredArgsConstructor
public class UnstableCaseService {

	static final int DEFAULT_DAYS = 7;

	public static final int MIN_FAILURE_EPISODES = 2;

	public static final int MIN_RETRIED_RUNS = 2;

	private final TestResultRepository results;

	private final TestCaseRepository testCases;

	private final EnvironmentRepository environments;

	private final ScheduleProperties scheduleProperties;

	private final Clock clock;

	/**
	 * @param from 시작일(포함). 생략 시 종료일 포함 최근 7일
	 * @param to 종료일(포함). 생략 시 오늘
	 * @param suiteId 지정하면 그 스위트의 케이스만
	 * @throws InvalidRequestException 잘못된 기간
	 */
	@Transactional(readOnly = true)
	public UnstableCases list(LocalDate from, LocalDate to, Long suiteId) {
		DatePeriod period = DatePeriod.of(from, to, DEFAULT_DAYS, clock, scheduleProperties.zone());
		List<Object[]> rows = results.caseStability(period.start(), period.end(), MIN_FAILURE_EPISODES,
				MIN_RETRIED_RUNS);
		Map<Long, TestCase> cases = new HashMap<>();
		Map<Long, Environment> environmentsById = new HashMap<>();
		if (!rows.isEmpty()) {
			testCases.findActiveWithSuiteByIdIn(rows.stream().map(row -> number(row[0])).distinct().toList())
				.forEach(testCase -> cases.put(testCase.getId(), testCase));
			environments.findAllById(rows.stream().map(row -> number(row[1])).distinct().toList())
				.stream()
				.filter(environment -> !environment.isArchived())
				.forEach(environment -> environmentsById.put(environment.getId(), environment));
		}
		List<UnstableCase> unstable = rows.stream()
			.filter(row -> cases.containsKey(number(row[0])) && environmentsById.containsKey(number(row[1])))
			.filter(row -> suiteId == null || cases.get(number(row[0])).getSuite().getId().equals(suiteId))
			.map(row -> UnstableCase.of(cases.get(number(row[0])), environmentsById.get(number(row[1])), row))
			.sorted(Comparator.comparingLong(UnstableCase::failureEpisodes)
				.thenComparingLong(UnstableCase::retriedRuns)
				.reversed()
				.thenComparing(unstableCase -> unstableCase.testCase().name()))
			.toList();
		return new UnstableCases(period.from(), period.to(), unstable);
	}

	private static long number(Object value) {
		return ((Number) value).longValue();
	}

	public record UnstableCases(LocalDate from, LocalDate to, List<UnstableCase> cases) {
	}

	/**
	 * @param runs 기간 안 스위트·환경의 스케줄 실행 수 (케이스를 기간 중에 추가했으면 실제 실행 수보다 많을 수 있음)
	 * @param failures 판정 실패·오류 수
	 * @param failureEpisodes 통과(또는 기간 시작)에서 실패로 바뀐 횟수
	 * @param retriedRuns 응답이 없어 다시 보낸 실행 수 (재시도 뒤 통과 포함)
	 */
	public record UnstableCase(IdName testCase, IdName suite, IdName environment, long runs, long failures,
			long failureEpisodes, long retriedRuns) {

		static UnstableCase of(TestCase testCase, Environment environment, Object[] row) {
			return new UnstableCase(new IdName(testCase.getId(), testCase.getName()),
					new IdName(testCase.getSuite().getId(), testCase.getSuite().getName()),
					new IdName(environment.getId(), environment.getName()), number(row[2]), number(row[3]),
					number(row[4]), number(row[5]));
		}

	}

}
