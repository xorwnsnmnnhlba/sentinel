package com.sentinel.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.sentinel.domain.TestResult;
import com.sentinel.domain.TestResult.ErrorReason;
import com.sentinel.domain.TestResult.Outcome;
import com.sentinel.domain.TestRun;
import com.sentinel.runner.HttpMethod;

public record TestRunDetail(long id, long suiteId, long environmentId, TestRun.Trigger trigger, TestRun.Status status,
		Instant startedAt, Instant finishedAt, Summary summary, List<Result> results) {

	static TestRunDetail of(TestRun run, List<TestResult> results) {
		List<Result> mapped = results.stream().map(Result::of).toList();
		return new TestRunDetail(run.getId(), run.getSuite().getId(), run.getEnvironment().getId(), run.getTrigger(),
				run.getStatus(), run.getStartedAt(), run.getFinishedAt(), Summary.of(mapped), mapped);
	}

	/**
	 * @param responded 응답 수신 건수. 4xx·5xx 포함, 판정은 3단계에서 추가
	 * @param errors 응답 미수신·전송 전 실패 건수
	 */
	public record Summary(int total, int responded, int errors) {

		static Summary of(List<Result> results) {
			int responded = (int) results.stream().filter(result -> result.outcome() == Outcome.RESPONDED).count();
			return new Summary(results.size(), responded, results.size() - responded);
		}

	}

	public record Result(long id, long testCaseId, Outcome outcome, ErrorReason errorReason, String errorMessage,
			Long elapsedMs, Request request, Response response) {

		static Result of(TestResult result) {
			Response response = result.getOutcome() == Outcome.RESPONDED
					? new Response(result.getStatusCode(), result.getResponseHeaders(), result.getResponseBody(),
							result.isResponseBodyTruncated())
					: null;
			return new Result(result.getId(), result.getTestCase().getId(), result.getOutcome(),
					result.getErrorReason(), result.getErrorMessage(), result.getElapsedMs(),
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
