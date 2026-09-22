package com.sentinel.application;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import com.sentinel.domain.TestResult.Verdict;
import com.sentinel.domain.TestRun;
import com.sentinel.domain.User;
import com.sentinel.infra.persistence.TestResultRepository;
import com.sentinel.infra.persistence.TestRunRepository;
import com.sentinel.infra.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 실행 이력 조회. 실행 건수와 무관하게 페이지당 쿼리 최대 4회(실행+이름, 전체 건수, 판정 집계, 실행 주체).
 * 결과가 한 페이지에 모두 들어가면 Spring Data가 전체 건수 쿼리를 생략하고, 수동 실행이 없으면 주체 조회도 생략.
 */
@Service
@RequiredArgsConstructor
public class RunQueryService {

	public static final int MAX_PAGE_SIZE = 100;

	private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("startedAt"), Sort.Order.desc("id"));

	private final TestRunRepository runs;

	private final TestResultRepository results;

	private final UserRepository users;

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
		Map<Long, IdName> triggeredBy = triggeredBy(found.getContent());
		return PageResponse.of(found,
				found.map(run -> summaryOf(run, counts.getOrDefault(run.getId(), Map.of()),
						triggeredBy.get(run.getTriggeredBy())))
					.getContent());
	}

	/** 실행 주체 이름. 삭제(보관)된 사용자도 이력 표시를 위해 조회 */
	private Map<Long, IdName> triggeredBy(List<TestRun> found) {
		List<Long> userIds = found.stream().map(TestRun::getTriggeredBy).filter(Objects::nonNull).distinct().toList();
		if (userIds.isEmpty()) {
			return Map.of();
		}
		return users.findAllById(userIds)
			.stream()
			.collect(Collectors.toMap(User::getId, user -> new IdName(user.getId(), user.getEmail())));
	}

	private static RunSummary summaryOf(TestRun run, Map<Verdict, Long> counts, IdName triggeredBy) {
		int passed = counts.getOrDefault(Verdict.PASSED, 0L).intValue();
		int failed = counts.getOrDefault(Verdict.FAILED, 0L).intValue();
		int errors = counts.getOrDefault(Verdict.ERROR, 0L).intValue();
		return new RunSummary(run.getId(),
				new IdName(run.getSuite().getProject().getId(), run.getSuite().getProject().getName()),
				new IdName(run.getSuite().getId(), run.getSuite().getName()),
				new IdName(run.getEnvironment().getId(), run.getEnvironment().getName()), run.getTrigger(),
				run.getStatus(), run.getStartedAt(), run.getFinishedAt(),
				new TestRunDetail.Summary(passed + failed + errors, passed, failed, errors), triggeredBy);
	}

}
