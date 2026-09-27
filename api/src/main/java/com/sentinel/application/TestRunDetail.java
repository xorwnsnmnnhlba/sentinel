package com.sentinel.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.sentinel.application.drift.ResultDrift;
import com.sentinel.application.drift.SchemaDriftService;
import com.sentinel.domain.TestResult;
import com.sentinel.domain.TestResult.ErrorReason;
import com.sentinel.domain.TestResult.Outcome;
import com.sentinel.domain.TestResult.Verdict;
import com.sentinel.domain.TestRun;
import com.sentinel.runner.HttpMethod;
import com.sentinel.runner.assertion.AssertionResult;
import com.sentinel.runner.schema.ResponseSchema;

/**
 * @param rawPurgedAt 보관기간이 지나 요청·응답 원문(헤더·본문)을 비운 시각. null이면 원문 보관 중
 * @param testCase 케이스 단건 실행의 대상. 스위트 전체 실행은 null
 */
public record TestRunDetail(long id, long suiteId, String suiteName, long environmentId, String environmentName,
		TestRun.Trigger trigger, TestRun.Status status, Instant startedAt, Instant finishedAt, Summary summary,
		List<Result> results, IdName triggeredBy, Instant rawPurgedAt, IdName testCase) {

	/**
	 * @param results 케이스가 함께 로딩된 결과. 트랜잭션 안에서 호출
	 * @param triggeredBy 수동 실행을 시작한 사용자. 스케줄 실행·이전 기록은 null
	 * @param testCase 케이스 단건 실행의 대상. 스위트 전체 실행은 null
	 */
	static TestRunDetail of(TestRun run, List<TestResult> results, IdName triggeredBy, IdName testCase) {
		return of(run, results, triggeredBy, testCase, Map.of());
	}

	/**
	 * @param drifts 결과 ID별 구조 변화 ({@link SchemaDriftService})
	 */
	static TestRunDetail of(TestRun run, List<TestResult> results, IdName triggeredBy, IdName testCase,
			Map<Long, ResultDrift> drifts) {
		List<Result> mapped = results.stream().map(result -> Result.of(result, drifts.get(result.getId()))).toList();
		return new TestRunDetail(run.getId(), run.getSuite().getId(), run.getSuite().getName(),
				run.getEnvironment().getId(), run.getEnvironment().getName(), run.getTrigger(), run.getStatus(),
				run.getStartedAt(), run.getFinishedAt(), Summary.of(mapped), mapped, triggeredBy, run.getRawPurgedAt(),
				testCase);
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

	/**
	 * @param responseSchema 응답 JSON의 구조 지문. 원문 보관기간이 지나도 유지. JSON이 아닌 응답·응답 없음은 null
	 * @param drift 이전 실행 대비 구조 변화. 지문이 없으면 null
	 */
	public record Result(long id, long testCaseId, String testCaseName, Verdict verdict, Outcome outcome,
			List<AssertionResult> assertionResults, ErrorReason errorReason, String errorMessage, Long elapsedMs,
			Request request, Response response, ResponseSchema responseSchema, ResultDrift drift) {

		static Result of(TestResult result, ResultDrift drift) {
			Response response = result.getOutcome() == Outcome.RESPONDED
					? new Response(result.getStatusCode(), result.getResponseHeaders(), result.getResponseBody(),
							result.isResponseBodyTruncated())
					: null;
			return new Result(result.getId(), result.getTestCase().getId(), result.getTestCase().getName(),
					result.getVerdict(), result.getOutcome(),
					result.getAssertionResults(), result.getErrorReason(), result.getErrorMessage(), result.getElapsedMs(),
					new Request(result.getRequestMethod(), result.getRequestUrl(), result.getRequestHeaders(),
							result.getRequestBody()),
					response, result.getResponseSchema(), drift);
		}

	}

	public record Request(HttpMethod method, String url, Map<String, List<String>> headers, String body) {
	}

	public record Response(int statusCode, Map<String, List<String>> headers, String body, boolean bodyTruncated) {
	}

}
