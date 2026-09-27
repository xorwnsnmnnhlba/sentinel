package com.sentinel.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.sentinel.domain.TestRun;
import com.sentinel.domain.TestSuite;
import com.sentinel.infra.persistence.TestRunRepository;
import com.sentinel.infra.persistence.TestSuiteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 스위트별 실행 요약 (대시보드). 기간 안에 시작한 실행과 그 결과의 판정 수, 통과율, 기간과 무관한 가장 최근 실행.
 * <p>
 * 스위트 수와 무관하게 쿼리 3회 (스위트, 실행 행의 판정 수(V10) 합계, 최근 실행). 결과 테이블은 읽지 않음
 */
@Service
@RequiredArgsConstructor
public class SuiteSummaryService {

	/** 기간 생략 시 최근 7일 */
	static final int DEFAULT_DAYS = 7;

	private final TestSuiteRepository suites;

	private final TestRunRepository runs;

	private final ScheduleProperties scheduleProperties;

	private final Clock clock;

	/**
	 * 보관되지 않은 모든 스위트 (프로젝트·스위트 이름 순). 실행이 없는 스위트도 포함
	 * @throws InvalidRequestException 잘못된 기간
	 */
	@Transactional(readOnly = true)
	public Summaries summaries(LocalDate from, LocalDate to) {
		DatePeriod period = DatePeriod.of(from, to, DEFAULT_DAYS, clock, scheduleProperties.zone());
		List<TestSuite> active = suites.findActiveWithProject();

		// 실행 수·실패 실행 수·판정별 결과 수를 실행 행의 판정 수(V10)로 한 번에 집계 (결과 테이블 미사용)
		Map<Long, TestRunRepository.SuiteRunTotals> totals = new HashMap<>();
		runs.summarizeBySuite(period.start(), period.end()).forEach(total -> totals.put(total.getSuiteId(), total));

		Map<Long, TestRun> latest = new HashMap<>();
		runs.findLatestPerSuite().forEach(run -> latest.put(run.getSuite().getId(), run));

		List<SuiteSummary> summaries = active.stream().map(suite -> {
			TestRunRepository.SuiteRunTotals total = totals.get(suite.getId());
			TestRun last = latest.get(suite.getId());
			return SuiteSummary.of(new IdName(suite.getId(), suite.getName()),
					new IdName(suite.getProject().getId(), suite.getProject().getName()),
					total == null ? 0 : total.getRuns(), total == null ? 0 : total.getFailingRuns(),
					total == null ? new TestRunDetail.Summary(0, 0, 0, 0)
							: summary(total.getPassed(), total.getFailed(), total.getErrors()),
					last == null ? null : LastRun.of(last,
							summary(last.getPassedCount(), last.getFailedCount(), last.getErrorCount())));
		}).toList();
		return new Summaries(period.from(), period.to(), summaries);
	}

	private static TestRunDetail.Summary summary(long passed, long failed, long errors) {
		return new TestRunDetail.Summary((int) (passed + failed + errors), (int) passed, (int) failed, (int) errors);
	}

	public record Summaries(LocalDate from, LocalDate to, List<SuiteSummary> suites) {
	}

	/**
	 * @param runs 기간 안에 시작한 실행 수
	 * @param failingRuns 그중 판정 실패·오류 결과가 있는 실행 수
	 * @param results 기간 안 실행의 판정별 결과 수
	 * @param passRate 통과 결과 비율(0~1). 결과가 없으면 null
	 * @param lastRun 기간과 무관한 가장 최근 실행. 실행한 적 없으면 null
	 */
	public record SuiteSummary(IdName suite, IdName project, long runs, long failingRuns,
			TestRunDetail.Summary results, Double passRate, LastRun lastRun) {

		static SuiteSummary of(IdName suite, IdName project, long runs, long failingRuns,
				TestRunDetail.Summary results, LastRun lastRun) {
			Double passRate = results.total() == 0 ? null : (double) results.passed() / results.total();
			return new SuiteSummary(suite, project, runs, failingRuns, results, passRate, lastRun);
		}

	}

	public record LastRun(long id, Instant startedAt, TestRun.Status status, TestRunDetail.Summary summary) {

		static LastRun of(TestRun run, TestRunDetail.Summary summary) {
			return new LastRun(run.getId(), run.getStartedAt(), run.getStatus(), summary);
		}

	}

}
