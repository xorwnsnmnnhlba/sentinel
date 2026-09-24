package com.sentinel.application.notification;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import com.sentinel.application.ScheduleProperties;
import com.sentinel.application.Transactions;
import com.sentinel.domain.TestResult;
import com.sentinel.domain.TestRun;
import com.sentinel.infra.persistence.TestResultRepository;
import com.sentinel.infra.persistence.TestRunRepository;
import com.sentinel.runner.assertion.AssertionResult;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

/**
 * 스케줄 실행 결과 알림.
 * <ul>
 * <li>실패·오류가 있으면 실패 알림. 케이스마다 새로 실패했는지, 몇 회째 연속인지 표시</li>
 * <li>모두 통과했고 직전 스케줄 실행에 실패가 있었으면 복구 알림</li>
 * <li>그 외(계속 통과)는 발송하지 않음</li>
 * </ul>
 * 비교 대상은 같은 스위트·환경의 이전 스케줄 실행(완료만). 수동 실행은 수정 확인용인 경우가 많아 제외하며, 넣으면 복구 알림이 누락될 수 있음
 */
@Component
@RequiredArgsConstructor
public class RunResultNotifier {

	/** 연속 실패를 거슬러 확인하는 최대 실행 수. 넘으면 "N회 이상"으로 표시 */
	static final int LOOKBACK = 20;

	private static final int MAX_REASON_LENGTH = 200;

	private final TestRunRepository runs;

	private final TestResultRepository results;

	private final Notifier notifier;

	private final NotificationProperties properties;

	private final ScheduleProperties scheduleProperties;

	private final Transactions transactions;

	public enum Sent {
		FAILURE, RECOVERY, NONE
	}

	/**
	 * @return 발송한 알림 종류
	 */
	public Sent notifyResult(long runId) {
		Optional<Message> message = transactions.readOnly(status -> messageOf(runId));
		message.ifPresent(found -> notifier.send(found.notification()));
		return message.map(Message::kind).orElse(Sent.NONE);
	}

	private Optional<Message> messageOf(long runId) {
		TestRun run = runs.findById(runId).orElseThrow(() -> new IllegalArgumentException("run " + runId + " not found"));
		List<TestResult> current = results.findWithTestCaseByRunId(runId);
		if (current.isEmpty()) {
			return Optional.empty();
		}
		List<TestRun> previous = runs.findBySuiteIdAndEnvironmentIdAndTriggerAndStatusAndIdLessThanOrderByIdDesc(
				run.getSuite().getId(), run.getEnvironment().getId(), TestRun.Trigger.SCHEDULED,
				TestRun.Status.COMPLETED, runId, Limit.of(LOOKBACK));
		Map<Long, Map<Long, Boolean>> failedByRun = failedCasesByRun(previous);

		List<TestResult> failing = current.stream().filter(RunResultNotifier::failed).toList();
		String suite = run.getSuite().getName();
		String environment = run.getEnvironment().getName();
		String runUrl = properties.runUrl(runId);
		if (!failing.isEmpty()) {
			List<RunReport.FailedCase> failures = failing.stream()
				.map(result -> failedCase(result, run, previous, failedByRun))
				.sorted(Comparator.comparingInt(RunReport.FailedCase::streak))
				.toList();
			return Optional.of(new Message(Sent.FAILURE, new RunReport(suite, environment, current.size(), failures,
					runUrl, scheduleProperties.zone()).toNotification()));
		}

		if (previous.isEmpty() || !failedByRun.getOrDefault(previous.getFirst().getId(), Map.of()).containsValue(true)) {
			return Optional.empty();
		}
		// 직전 실행부터 거슬러 올라가며 실패가 있던 실행 수와 첫 실패 시각
		int failingRuns = 0;
		TestRun firstFailing = previous.getFirst();
		for (TestRun earlier : previous) {
			if (!failedByRun.getOrDefault(earlier.getId(), Map.of()).containsValue(true)) {
				break;
			}
			failingRuns++;
			firstFailing = earlier;
		}
		Map<Long, Boolean> lastFailures = failedByRun.get(previous.getFirst().getId());
		List<String> recovered = current.stream()
			.filter(result -> Boolean.TRUE.equals(lastFailures.get(result.getTestCase().getId())))
			.map(result -> result.getTestCase().getName())
			.toList();
		return Optional.of(new Message(Sent.RECOVERY,
				new RecoveryReport(suite, environment, current.size(), recovered, failingRuns,
						failingRuns == previous.size() && previous.size() == LOOKBACK, firstFailing.getStartedAt(),
						run.getStartedAt(), runUrl, scheduleProperties.zone())
					.toNotification()));
	}

	/**
	 * 케이스의 연속 실패. 현재 실행부터 이전 실행을 거슬러 올라가며 실패가 이어진 횟수와 첫 실패 시각
	 */
	private RunReport.FailedCase failedCase(TestResult result, TestRun run, List<TestRun> previous,
			Map<Long, Map<Long, Boolean>> failedByRun) {
		long caseId = result.getTestCase().getId();
		int streak = 1;
		TestRun since = run;
		for (TestRun earlier : previous) {
			if (!Boolean.TRUE.equals(failedByRun.getOrDefault(earlier.getId(), Map.of()).get(caseId))) {
				break;
			}
			streak++;
			since = earlier;
		}
		boolean capped = streak == previous.size() + 1 && previous.size() == LOOKBACK;
		return new RunReport.FailedCase(result.getTestCase().getName(), result.getRequestMethod(),
				result.getTestCase().getRequest().getPath(), reasonOf(result), streak, capped, since.getStartedAt());
	}

	/** 실행별 케이스의 실패 여부 (true: 판정 실패·오류) */
	private Map<Long, Map<Long, Boolean>> failedCasesByRun(List<TestRun> previous) {
		Map<Long, Map<Long, Boolean>> failed = new HashMap<>();
		if (previous.isEmpty()) {
			return failed;
		}
		for (TestResultRepository.CaseVerdict verdict : results
			.findCaseVerdictsByRunIds(previous.stream().map(TestRun::getId).toList())) {
			failed.computeIfAbsent(verdict.getRunId(), id -> new HashMap<>())
				.put(verdict.getCaseId(), failed(verdict.getVerdict()));
		}
		return failed;
	}

	private static boolean failed(TestResult result) {
		return failed(result.getVerdict());
	}

	private static boolean failed(TestResult.Verdict verdict) {
		return verdict == TestResult.Verdict.FAILED || verdict == TestResult.Verdict.ERROR;
	}

	private static String reasonOf(TestResult result) {
		String reason;
		if (result.getVerdict() == TestResult.Verdict.ERROR) {
			reason = result.getErrorReason() + (result.getErrorMessage() == null ? "" : ": " + result.getErrorMessage());
		}
		else {
			reason = result.getAssertionResults()
				.stream()
				.filter(assertion -> !assertion.passed())
				.map(RunResultNotifier::describe)
				.collect(Collectors.joining("; "));
		}
		return reason.length() <= MAX_REASON_LENGTH ? reason : reason.substring(0, MAX_REASON_LENGTH) + "…";
	}

	private static String describe(AssertionResult assertion) {
		return assertion.message() == null ? assertion.description()
				: assertion.description() + ": " + assertion.message();
	}

	private record Message(Sent kind, Notification notification) {
	}

}
