package com.sentinel.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * @param throughCase 여기까지 실행의 마지막 케이스. 그 외 실행은 null
 * @param canceledBy 실행을 중지한 사용자. 중지된 실행이 아니면 null (중지 시각은 finishedAt)
 * @param plannedCount 실행할 케이스 수. 진행 중이면 results 수와 비교해 진행률 표시. 이전 실행은 null
 * @param previousRun 비교 기준인 직전 실행 (같은 스위트·환경의 완료된 스위트 전체 실행). 없거나 스위트 전체 실행이 아니면 null
 * @param rerunOf 실패 다시 실행의 원래 실행 ID. 그 외 실행은 null
 */
public record TestRunDetail(long id, long suiteId, String suiteName, long environmentId, String environmentName,
		TestRun.Trigger trigger, TestRun.Status status, Instant startedAt, Instant finishedAt, Summary summary,
		List<Result> results, IdName triggeredBy, IdName canceledBy, Instant rawPurgedAt, IdName testCase,
		IdName throughCase, Integer plannedCount, PreviousRun previousRun, Long rerunOf) {

	/**
	 * @param results 케이스가 함께 로딩된 결과. 트랜잭션 안에서 호출
	 * @param triggeredBy 수동 실행을 시작한 사용자. 스케줄 실행·이전 기록은 null
	 * @param testCase 케이스 단건 실행의 대상. 스위트 전체 실행은 null
	 * @param throughCase 여기까지 실행의 마지막 케이스. 그 외 실행은 null
	 * @param drifts 결과 ID별 구조 변화 ({@link SchemaDriftService})
	 * @param previous 직전 실행과 그 실행의 케이스별 판정. 비교하지 않으면 null
	 * @param rerunTargets 실패 다시 실행이면 원래 실행에서 실패·오류가 난 케이스. 그 외 실행은 null
	 */
	static TestRunDetail of(TestRun run, List<TestResult> results, IdName triggeredBy, IdName canceledBy,
			IdName testCase, IdName throughCase, Map<Long, ResultDrift> drifts, Previous previous,
			Set<Long> rerunTargets) {
		List<Result> mapped = results.stream()
			.map(result -> Result.of(result, drifts.get(result.getId()),
					previous == null ? null : Change.of(previous.verdicts(), result),
					rerunTargets != null && !rerunTargets.contains(result.getTestCase().getId())))
			.toList();
		return new TestRunDetail(run.getId(), run.getSuite().getId(), run.getSuite().getName(),
				run.getEnvironment().getId(), run.getEnvironment().getName(), run.getTrigger(), run.getStatus(),
				run.getStartedAt(), run.getFinishedAt(), Summary.of(mapped), mapped, triggeredBy, canceledBy,
				run.getRawPurgedAt(),
				testCase, throughCase, run.getPlannedCount(), previous == null ? null : previous.run(),
				run.getRerunOfRunId());
	}

	/**
	 * 비교 기준
	 * @param verdicts 직전 실행의 케이스 ID별 판정 (판정 없는 이전 기록은 빠짐)
	 */
	record Previous(PreviousRun run, Map<Long, Verdict> verdicts) {
	}

	public record PreviousRun(long id, Instant startedAt) {
	}

	/**
	 * 직전 실행 대비 케이스 판정 변화. 실패는 판정 실패·오류
	 */
	public enum Change {

		/** 직전 실행에 없던 케이스 (새로 추가 등) */
		NEW,
		/** 통과 → 실패 */
		NEWLY_FAILING,
		/** 실패 → 통과 */
		RECOVERED,
		/** 실패 → 실패 */
		STILL_FAILING,
		/** 통과 → 통과 */
		UNCHANGED;

		static Change of(Map<Long, Verdict> previous, TestResult result) {
			if (!previous.containsKey(result.getTestCase().getId())) {
				return NEW;
			}
			boolean wasFailing = failing(previous.get(result.getTestCase().getId()));
			boolean failing = failing(result.getVerdict());
			if (wasFailing) {
				return failing ? STILL_FAILING : RECOVERED;
			}
			return failing ? NEWLY_FAILING : UNCHANGED;
		}

		private static boolean failing(Verdict verdict) {
			return verdict == Verdict.FAILED || verdict == Verdict.ERROR;
		}

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
	 * @param change 직전 실행 대비 판정 변화. 비교하지 않는 실행(직전 실행 없음, 단건·여기까지·실패 다시 실행)은 null
	 * @param supporting 실패 다시 실행에서 원래 실행에 실패하지 않았지만 다시 실행하는 케이스가 쓰는 추출 값을 만들기 위해
	 *     함께 실행한 케이스. 그 외는 false
	 */
	public record Result(long id, long testCaseId, String testCaseName, Verdict verdict, Outcome outcome,
			List<AssertionResult> assertionResults, ErrorReason errorReason, String errorMessage, Long elapsedMs,
			Request request, Response response, ResponseSchema responseSchema, ResultDrift drift, Change change,
			boolean supporting) {

		static Result of(TestResult result, ResultDrift drift, Change change, boolean supporting) {
			Response response = result.getOutcome() == Outcome.RESPONDED
					? new Response(result.getStatusCode(), result.getResponseHeaders(), result.getResponseBody(),
							result.isResponseBodyTruncated())
					: null;
			return new Result(result.getId(), result.getTestCase().getId(), result.getTestCase().getName(),
					result.getVerdict(), result.getOutcome(),
					result.getAssertionResults(), result.getErrorReason(), result.getErrorMessage(), result.getElapsedMs(),
					new Request(result.getRequestMethod(), result.getRequestUrl(), result.getRequestHeaders(),
							result.getRequestBody()),
					response, result.getResponseSchema(), drift, change, supporting);
		}

	}

	public record Request(HttpMethod method, String url, Map<String, List<String>> headers, String body) {
	}

	public record Response(int statusCode, Map<String, List<String>> headers, String body, boolean bodyTruncated) {
	}

}
