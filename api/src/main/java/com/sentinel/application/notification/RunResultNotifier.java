package com.sentinel.application.notification;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
import com.sentinel.infra.persistence.TestResultQueries;
import com.sentinel.infra.persistence.TestResultRepository;
import com.sentinel.infra.persistence.TestRunRepository;
import com.sentinel.runner.assertion.AssertionResult;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 스케줄 실행 결과 알림.
 * <ul>
 * <li>실패·오류가 있으면 실패 알림. 케이스마다 새로 실패했는지, 몇 회째 연속인지 표시</li>
 * <li>단, 새로 실패한 케이스 없이 계속 실패 중이면 재알림 간격({@link NotificationProperties#repeatInterval()}) 안에
 * 같은 스위트·환경의 실패 알림을 보냈을 때 생략. 일부만 복구된 경우도 생략하고 다음 재알림에 남은 실패만 표시</li>
 * <li>모두 통과했고 직전 스케줄 실행에 실패가 있었으면 복구 알림</li>
 * <li>그 외(계속 통과)는 발송하지 않음</li>
 * </ul>
 * 프로젝트에 Webhook이 있으면 그 채널로, 없으면 서버 기본 채널로 발송 ({@link NotificationChannels}).
 * 비교 대상은 같은 스위트·환경의 이전 스케줄 실행(완료만). 수동 실행은 수정 확인용인 경우가 많아 제외하며, 넣으면 복구 알림이 누락될 수 있음
 */
@Component
@RequiredArgsConstructor
public class RunResultNotifier {

	private static final Logger log = LoggerFactory.getLogger(RunResultNotifier.class);

	/** 연속 실패를 거슬러 확인하는 최대 실행 수. 넘으면 "N회 이상"으로 표시 */
	static final int LOOKBACK = 20;

	private static final int MAX_REASON_LENGTH = 200;

	private final TestRunRepository runs;

	private final TestResultRepository results;

	private final NotificationChannels channels;

	private final NotificationProperties properties;

	private final ScheduleProperties scheduleProperties;

	private final Transactions transactions;

	private final Clock clock;

	/** SUPPRESSED: 계속 실패 중이라 재알림 간격까지 실패 알림 생략 */
	public enum Sent {
		FAILURE, RECOVERY, SUPPRESSED, NONE
	}

	/**
	 * @return 발송한 알림 종류
	 */
	public Sent notifyResult(long runId) {
		Optional<Message> message = transactions.readOnly(status -> messageOf(runId));
		if (message.isEmpty()) {
			return Sent.NONE;
		}
		Message found = message.get();
		if (found.kind() == Sent.SUPPRESSED) {
			log.info("Skipped failure notification for run {}: still failing and notified within {}", runId,
					properties.repeatInterval());
			return Sent.SUPPRESSED;
		}
		found.channel().send(found.notification());
		if (found.kind() == Sent.FAILURE) {
			// 발송 시도 기준으로 기록 (채널 오류는 채널이 로그로 남김). 다음 재알림 간격 판단용
			transactions.writeWithoutResult(status -> runs.findById(runId)
				.ifPresent(run -> run.markFailureNotified(clock.instant())));
		}
		return found.kind();
	}

	private Optional<Message> messageOf(long runId) {
		TestRun run = runs.findById(runId).orElseThrow(() -> new IllegalArgumentException("run " + runId + " not found"));
		// 중지된 실행은 사람이 멈춘 것이라 일부 케이스만의 결과로 실패·복구를 알리지 않음
		if (run.getStatus() != TestRun.Status.COMPLETED) {
			return Optional.empty();
		}
		List<TestResult> current = results.findWithTestCaseByRunId(runId);
		if (current.isEmpty()) {
			return Optional.empty();
		}
		List<TestRun> previous = runs.findPreviousRuns(run.getSuite().getId(),
				run.getEnvironment().getId(), TestRun.Trigger.SCHEDULED, TestRun.Status.COMPLETED, runId, LOOKBACK);
		Map<Long, Map<Long, Boolean>> failedByRun = failedCasesByRun(previous);

		List<TestResult> failing = current.stream().filter(RunResultNotifier::failed).toList();
		String suite = run.getSuite().getName();
		String environment = run.getEnvironment().getName();
		String runUrl = properties.runUrl(runId);
		// 프로젝트 Webhook이 있으면 그 채널. 암호문 조회는 이 트랜잭션에서, 발송은 트랜잭션 밖에서
		Notifier channel = channels.forProject(run.getSuite().getProject());
		if (!failing.isEmpty()) {
			List<RunReport.FailedCase> failures = failing.stream()
				.map(result -> failedCase(result, run, previous, failedByRun))
				.sorted(Comparator.comparingInt(RunReport.FailedCase::streak))
				.toList();
			if (recentlyNotified(run, failures)) {
				return Optional.of(new Message(Sent.SUPPRESSED, null, channel));
			}
			Duration repeatInterval = properties.repeatInterval().isZero() ? null : properties.repeatInterval();
			return Optional.of(new Message(Sent.FAILURE, new RunReport(suite, environment, current.size(), failures,
					runUrl, scheduleProperties.zone(), repeatInterval).toNotification(), channel));
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
					.toNotification(),
				channel));
	}

	/**
	 * 새로 실패한 케이스 없이 계속 실패 중이고, 재알림 간격 안에 같은 스위트·환경의 실패 알림을 보냈는지.
	 * 실행 시작 시각이 폴링 주기만큼 늦어져도 간격마다 다시 알리도록 간격에서 폴링 주기를 뺀 범위만 확인
	 */
	private boolean recentlyNotified(TestRun run, List<RunReport.FailedCase> failures) {
		Duration repeatInterval = properties.repeatInterval();
		if (repeatInterval.isZero() || failures.stream().anyMatch(failure -> failure.streak() == 1)) {
			return false;
		}
		Instant after = run.getStartedAt().minus(repeatInterval).plus(scheduleProperties.pollInterval());
		return runs.existsFailureNotifiedSince(run.getSuite().getId(), run.getEnvironment().getId(), after,
				run.getId());
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
		for (TestResultQueries.CaseVerdict verdict : results
			.findCaseVerdictsByRunIds(previous.stream().map(TestRun::getId).toList())) {
			failed.computeIfAbsent(verdict.runId(), id -> new HashMap<>())
				.put(verdict.caseId(), failed(verdict.verdict()));
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
		if (reason.length() > MAX_REASON_LENGTH) {
			reason = reason.substring(0, MAX_REASON_LENGTH) + "…";
		}
		// 일시적인 흔들림이 아니라 다시 보내도 응답이 없었음을 알림
		return result.getRetryCount() == 0 ? reason : reason + " (" + result.getRetryCount() + "회 다시 보낸 뒤)";
	}

	private static String describe(AssertionResult assertion) {
		return assertion.message() == null ? assertion.description()
				: assertion.description() + ": " + assertion.message();
	}

	private record Message(Sent kind, Notification notification, Notifier channel) {
	}

}
