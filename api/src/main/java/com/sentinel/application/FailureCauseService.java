package com.sentinel.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
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
 * 실패 원인 분류. 기간 안 스케줄 실행의 실패·오류 결과를 원인별로 모아, 알림이 왔을 때 GW·네트워크 문제인지 API 문제인지
 * 케이스·환경 설정 문제인지 먼저 가름. 수동 실행은 수정 확인용이 많아 제외 (불안정 케이스와 같은 기준). 삭제된 케이스·환경 제외
 */
@Service
@RequiredArgsConstructor
public class FailureCauseService {

	static final int DEFAULT_DAYS = 7;

	/** 원인마다 보여 줄 케이스·환경 수 (결과가 많은 순) */
	static final int TOP_CASES = 3;

	private final TestResultRepository results;

	private final TestCaseRepository testCases;

	private final EnvironmentRepository environments;

	private final ScheduleProperties scheduleProperties;

	private final Clock clock;

	/**
	 * @param from 시작일(포함). 생략 시 종료일 포함 최근 7일
	 * @param to 종료일(포함). 생략 시 오늘
	 * @param suiteId 지정하면 그 스위트의 케이스만
	 * @return 결과가 많은 원인 순. 결과가 없는 원인은 생략
	 * @throws InvalidRequestException 잘못된 기간
	 */
	@Transactional(readOnly = true)
	public FailureCauses list(LocalDate from, LocalDate to, Long suiteId) {
		DatePeriod period = DatePeriod.of(from, to, DEFAULT_DAYS, clock, scheduleProperties.zone());
		List<Object[]> rows = results.failureCauses(period.start(), period.end());
		Map<Long, TestCase> cases = new HashMap<>();
		Map<Long, Environment> environmentsById = new HashMap<>();
		if (!rows.isEmpty()) {
			testCases.findActiveWithSuiteByIdIn(rows.stream().map(row -> number(row[1])).distinct().toList())
				.forEach(testCase -> cases.put(testCase.getId(), testCase));
			environments.findAllById(rows.stream().map(row -> number(row[2])).distinct().toList())
				.stream()
				.filter(environment -> !environment.isArchived())
				.forEach(environment -> environmentsById.put(environment.getId(), environment));
		}
		Map<Cause, List<CaseCount>> byCause = new EnumMap<>(Cause.class);
		for (Object[] row : rows) {
			TestCase testCase = cases.get(number(row[1]));
			Environment environment = environmentsById.get(number(row[2]));
			if (testCase == null || environment == null
					|| (suiteId != null && !testCase.getSuite().getId().equals(suiteId))) {
				continue;
			}
			byCause.computeIfAbsent(Cause.valueOf((String) row[0]), cause -> new ArrayList<>())
				.add(CaseCount.of(testCase, environment, number(row[3])));
		}
		List<CauseSummary> causes = byCause.entrySet()
			.stream()
			.map(entry -> CauseSummary.of(entry.getKey(), entry.getValue()))
			.sorted(Comparator.comparingLong(CauseSummary::results).reversed().thenComparing(CauseSummary::cause))
			.toList();
		return new FailureCauses(period.from(), period.to(), causes.stream().mapToLong(CauseSummary::results).sum(),
				causes);
	}

	private static long number(Object value) {
		return ((Number) value).longValue();
	}

	/** 원인 묶음. 알림을 받았을 때 먼저 볼 곳 */
	public enum Group {
		/** 응답을 받지 못함: GW·네트워크·대상 서버 상태 */
		NETWORK,
		/** 응답은 받았으나 판정 실패: API 동작 변경·장애 */
		API,
		/** 요청을 만들지 못함: 케이스·환경 설정 */
		SETUP,
		/** Sentinel 내부 오류 */
		OTHER
	}

	public enum Cause {
		TIMEOUT(Group.NETWORK), CONNECTION_FAILED(Group.NETWORK), CONNECTION_LOST(Group.NETWORK),
		SERVER_ERROR(Group.API), CLIENT_ERROR(Group.API), ASSERTION_MISMATCH(Group.API), CONFIGURATION(Group.SETUP),
		OTHER(Group.OTHER);

		private final Group group;

		Cause(Group group) {
			this.group = group;
		}

		public Group group() {
			return group;
		}

	}

	/** @param results 기간 안 실패·오류 결과 수 */
	public record FailureCauses(LocalDate from, LocalDate to, long results, List<CauseSummary> causes) {
	}

	/**
	 * @param results 이 원인의 실패·오류 결과 수
	 * @param caseCount 이 원인으로 실패한 케이스·환경 수
	 * @param cases 결과가 많은 케이스·환경 (최대 {@link #TOP_CASES}건)
	 */
	public record CauseSummary(Cause cause, Group group, long results, int caseCount, List<CaseCount> cases) {

		static CauseSummary of(Cause cause, List<CaseCount> counts) {
			List<CaseCount> sorted = counts.stream()
				.sorted(Comparator.comparingLong(CaseCount::results)
					.reversed()
					.thenComparing(count -> count.testCase().name()))
				.toList();
			return new CauseSummary(cause, cause.group(), counts.stream().mapToLong(CaseCount::results).sum(),
					counts.size(), sorted.stream().limit(TOP_CASES).toList());
		}

	}

	public record CaseCount(IdName testCase, IdName suite, IdName environment, long results) {

		static CaseCount of(TestCase testCase, Environment environment, long results) {
			return new CaseCount(new IdName(testCase.getId(), testCase.getName()),
					new IdName(testCase.getSuite().getId(), testCase.getSuite().getName()),
					new IdName(environment.getId(), environment.getName()), results);
		}

	}

}
