package com.sentinel.application.notification;

import java.util.List;
import java.util.stream.Collectors;

import com.sentinel.application.Transactions;
import com.sentinel.domain.TestResult;
import com.sentinel.domain.TestRun;
import com.sentinel.infra.persistence.TestResultRepository;
import com.sentinel.infra.persistence.TestRunRepository;
import com.sentinel.runner.assertion.AssertionResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 실행 결과에 판정 실패·오류가 있을 때만 알림 발송. 성공 알림은 발송하지 않음.
 */
@Component
@RequiredArgsConstructor
public class RunFailureNotifier {

	private static final int MAX_REASON_LENGTH = 200;

	private final TestRunRepository runs;

	private final TestResultRepository results;

	private final Notifier notifier;

	private final Transactions transactions;

	/**
	 * @return 알림 발송 여부
	 */
	public boolean notifyIfFailed(long runId) {
		RunReport report = transactions.readOnly(status -> reportOf(runId));
		if (!report.hasFailures()) {
			return false;
		}
		notifier.send(report.toNotification());
		return true;
	}

	private RunReport reportOf(long runId) {
		TestRun run = runs.findById(runId).orElseThrow(() -> new IllegalArgumentException("run " + runId + " not found"));
		List<TestResult> all = results.findByRunIdOrderById(runId);
		List<RunReport.FailedCase> failures = all.stream()
			.filter(result -> result.getVerdict() == TestResult.Verdict.FAILED
					|| result.getVerdict() == TestResult.Verdict.ERROR)
			.map(result -> new RunReport.FailedCase(result.getRequestMethod(),
					result.getTestCase().getRequest().getPath(), reasonOf(result)))
			.toList();
		return new RunReport(run.getSuite().getName(), run.getEnvironment().getName(), all.size(), failures);
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
				.map(RunFailureNotifier::describe)
				.collect(Collectors.joining("; "));
		}
		return reason.length() <= MAX_REASON_LENGTH ? reason : reason.substring(0, MAX_REASON_LENGTH) + "…";
	}

	private static String describe(AssertionResult assertion) {
		return assertion.message() == null ? assertion.description()
				: assertion.description() + ": " + assertion.message();
	}

}
