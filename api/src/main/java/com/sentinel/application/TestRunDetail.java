package com.sentinel.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.sentinel.domain.TestResult;
import com.sentinel.domain.TestResult.ErrorReason;
import com.sentinel.domain.TestResult.Outcome;
import com.sentinel.domain.TestResult.Verdict;
import com.sentinel.domain.TestRun;
import com.sentinel.runner.HttpMethod;
import com.sentinel.runner.assertion.AssertionResult;

/**
 * @param rawPurgedAt 보관기간이 지나 요청·응답 원문(헤더·본문)을 비운 시각. null이면 원문 보관 중
 */
public record TestRunDetail(long id, long suiteId, String suiteName, long environmentId, String environmentName,
		TestRun.Trigger trigger, TestRun.Status status, Instant startedAt, Instant finishedAt, Summary summary,
		List<Result> results, IdName triggeredBy, Instant rawPurgedAt) {

	/**
	 * @param results 케이스가 함께 로딩된 결과. 트랜잭션 안에서 호출
	 * @param triggeredBy 수동 실행을 시작한 사용자. 스케줄 실행·이전 기록은 null
	 */
	static TestRunDetail of(TestRun run, List<TestResult> results, IdName triggeredBy) {
		List<Result> mapped = results.stream().map(Result::of).toList();
		return new TestRunDetail(run.getId(), run.getSuite().getId(), run.getSuite().getName(),
				run.getEnvironment().getId(), run.getEnvironment().getName(), run.getTrigger(), run.getStatus(),
				run.getStartedAt(), run.getFinishedAt(), Summary.of(mapped), mapped, triggeredBy, run.getRawPurgedAt());
	}

	/**
	 * @param passed 모든 판정 규칙 통과
	 * @param failed 응답 수신 후 판정 실패
	 * @param errors 응답 미수신·전송 전 실패
	 */
	public record Summary(int total, int passed, int failed, int errors) {

		static Summary of(List<Result> results) {
			return new Summary(results.size(), count(results, Verdict.PASSED), count(results, Verdict.FAILED),
					count(results, Verdict.ERROR));
		}

		private static int count(List<Result> results, Verdict verdict) {
			return (int) results.stream().filter(result -> result.verdict() == verdict).count();
		}

	}

	public record Result(long id, long testCaseId, String testCaseName, Verdict verdict, Outcome outcome,
			List<AssertionResult> assertionResults, ErrorReason errorReason, String errorMessage, Long elapsedMs,
			Request request, Response response) {

		static Result of(TestResult result) {
			Response response = result.getOutcome() == Outcome.RESPONDED
					? new Response(result.getStatusCode(), result.getResponseHeaders(), result.getResponseBody(),
							result.isResponseBodyTruncated())
					: null;
			return new Result(result.getId(), result.getTestCase().getId(), result.getTestCase().getName(),
					result.getVerdict(), result.getOutcome(),
					result.getAssertionResults(), result.getErrorReason(), result.getErrorMessage(), result.getElapsedMs(),
					new Request(result.getRequestMethod(), result.getRequestUrl(), result.getRequestHeaders(),
							result.getRequestBody()),
					response);
		}

	}

	public record Request(HttpMethod method, String url, Map<String, List<String>> headers, String body) {
	}

	public record Response(int statusCode, Map<String, List<String>> headers, String body, boolean bodyTruncated) {
	}

}
