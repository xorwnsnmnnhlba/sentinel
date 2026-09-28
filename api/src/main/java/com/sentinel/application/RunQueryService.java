package com.sentinel.application;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.sentinel.domain.TestCase;
import com.sentinel.domain.TestRun;
import com.sentinel.domain.User;
import com.sentinel.infra.persistence.TestCaseRepository;
import com.sentinel.infra.persistence.TestRunRepository;
import com.sentinel.infra.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 실행 이력 조회. 실행 건수와 무관하게 페이지당 쿼리 최대 4회(실행+이름, 전체 건수, 실행 주체, 단건·여기까지 실행 대상).
 * 판정 수는 실행 행에 보관한 값(V10)이라 결과 테이블을 읽지 않음.
 * 결과가 한 페이지에 모두 들어가면 Spring Data가 전체 건수 쿼리를 생략하고, 수동 실행·단건 실행이 없으면 해당 조회도 생략.
 */
@Service
@RequiredArgsConstructor
public class RunQueryService {

	public static final int MAX_PAGE_SIZE = 100;

	private final TestRunRepository runs;

	private final UserRepository users;

	private final TestCaseRepository testCases;

	/** 기간 조건을 해석할 시간대. 스케줄 cron과 같은 기준 사용 */
	private final ScheduleProperties scheduleProperties;

	/**
	 * @param filter null인 조건은 무시. 기간은 스케줄 시간대(기본 Asia/Seoul) 기준
	 * @param page 0부터 시작, 음수는 0으로 보정
	 * @param size 1~{@link #MAX_PAGE_SIZE}로 보정
	 */
	@Transactional(readOnly = true)
	public PageResponse<RunSummary> listRuns(RunFilter filter, int page, int size) {
		PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE));
		Page<TestRun> found = runs.findNewestFirst(filter.toPredicate(scheduleProperties.zone()), pageable);

		Map<Long, IdName> triggeredBy = triggeredBy(found.getContent());
		Map<Long, IdName> targetCases = targetCases(found.getContent());
		return PageResponse.of(found,
				found.map(run -> summaryOf(run,
						// 스케줄 실행은 주체가, 스위트 전체 실행은 대상 케이스가 null. 불변 Map은 null 키 조회 시 예외이므로 먼저 확인
						run.getTriggeredBy() == null ? null : triggeredBy.get(run.getTriggeredBy()),
						run.isSingleCase() ? targetCases.get(run.getTestCaseId()) : null,
						run.isThroughCase() ? targetCases.get(run.getThroughCaseId()) : null))
					.getContent());
	}

	/** 단건 실행·여기까지 실행의 대상 케이스 이름. 삭제(보관)된 케이스도 이력 표시를 위해 조회 */
	private Map<Long, IdName> targetCases(List<TestRun> found) {
		List<Long> caseIds = found.stream()
			.flatMap(run -> Stream.of(run.getTestCaseId(), run.getThroughCaseId()))
			.filter(Objects::nonNull)
			.distinct()
			.toList();
		if (caseIds.isEmpty()) {
			return Map.of();
		}
		return testCases.findAllById(caseIds)
			.stream()
			.collect(Collectors.toMap(TestCase::getId, testCase -> new IdName(testCase.getId(), testCase.getName())));
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

	private static RunSummary summaryOf(TestRun run, IdName triggeredBy, IdName testCase, IdName throughCase) {
		int passed = run.getPassedCount();
		int failed = run.getFailedCount();
		int errors = run.getErrorCount();
		return new RunSummary(run.getId(),
				new IdName(run.getSuite().getProject().getId(), run.getSuite().getProject().getName()),
				new IdName(run.getSuite().getId(), run.getSuite().getName()),
				new IdName(run.getEnvironment().getId(), run.getEnvironment().getName()), run.getTrigger(),
				run.getStatus(), run.getStartedAt(), run.getFinishedAt(),
				new TestRunDetail.Summary(passed + failed + errors, passed, failed, errors), triggeredBy, testCase,
				throughCase, run.getPlannedCount());
	}

}
