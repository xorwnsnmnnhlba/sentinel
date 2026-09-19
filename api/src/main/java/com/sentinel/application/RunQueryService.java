package com.sentinel.application;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.sentinel.domain.TestResult.Verdict;
import com.sentinel.domain.TestRun;
import com.sentinel.infra.persistence.TestResultRepository;
import com.sentinel.infra.persistence.TestRunRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 실행 이력 조회. 실행 건수와 무관하게 페이지당 쿼리 최대 3회(실행+이름, 전체 건수, 판정 집계).
 * 결과가 한 페이지에 모두 들어가면 Spring Data가 전체 건수 쿼리를 생략하여 2회.
 */
@Service
public class RunQueryService {

	public static final int MAX_PAGE_SIZE = 100;

	private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("startedAt"), Sort.Order.desc("id"));

	private final TestRunRepository runs;

	private final TestResultRepository results;

	RunQueryService(TestRunRepository runs, TestResultRepository results) {
		this.runs = runs;
		this.results = results;
	}

	/**
	 * @param suiteId null이면 전체 스위트
	 * @param page 0부터 시작, 음수는 0으로 보정
	 * @param size 1~{@link #MAX_PAGE_SIZE}로 보정
	 */
	@Transactional(readOnly = true)
	public PageResponse<RunSummary> listRuns(Long suiteId, int page, int size) {
		PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE), NEWEST_FIRST);
		Page<TestRun> found = suiteId == null ? runs.findAllBy(pageable) : runs.findBySuiteId(suiteId, pageable);

		Map<Long, Map<Verdict, Long>> counts = new HashMap<>();
		if (found.hasContent()) {
			List<Long> runIds = found.map(TestRun::getId).getContent();
			for (TestResultRepository.VerdictCount count : results.countVerdictsByRunIds(runIds)) {
				if (count.getVerdict() != null) {
					counts.computeIfAbsent(count.getRunId(), id -> new EnumMap<>(Verdict.class))
						.put(count.getVerdict(), count.getCount());
				}
			}
		}
		return PageResponse.of(found, found.map(run -> summaryOf(run, counts.getOrDefault(run.getId(), Map.of())))
			.getContent());
	}

	private static RunSummary summaryOf(TestRun run, Map<Verdict, Long> counts) {
		int passed = counts.getOrDefault(Verdict.PASSED, 0L).intValue();
		int failed = counts.getOrDefault(Verdict.FAILED, 0L).intValue();
		int errors = counts.getOrDefault(Verdict.ERROR, 0L).intValue();
		return new RunSummary(run.getId(),
				new IdName(run.getSuite().getProject().getId(), run.getSuite().getProject().getName()),
				new IdName(run.getSuite().getId(), run.getSuite().getName()),
				new IdName(run.getEnvironment().getId(), run.getEnvironment().getName()), run.getTrigger(),
				run.getStatus(), run.getStartedAt(), run.getFinishedAt(),
				new TestRunDetail.Summary(passed + failed + errors, passed, failed, errors));
	}

}
