package com.sentinel.domain;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.sentinel.runner.HttpExchange;
import com.sentinel.runner.HttpMethod;
import com.sentinel.runner.ResolvedRequest;
import com.sentinel.runner.assertion.AssertionResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestResultTest {

	private final Project project = new Project("billing", null);

	private final TestSuite suite = new TestSuite(project, "charge", null);

	private final TestCase testCase = new TestCase(suite, "case",
			new RequestSpec(HttpMethod.GET, "/a", null, null));

	private final TestRun run = TestRun.start(suite, new Environment(project, "dev", "https://gw"),
			TestRun.Trigger.MANUAL, java.time.Instant.now());

	@Test
	void keepsBodyUpToLimit() {
		String body = "a".repeat(TestResult.MAX_BODY_BYTES);

		TestResult result = TestResult.responded(run, testCase, exchange(body, Map.of()), List.of());

		assertThat(result.getResponseBody()).isEqualTo(body);
		assertThat(result.isResponseBodyTruncated()).isFalse();
	}

	@Test
	void truncatesBodyOverLimitWithoutSplittingMultibyteCharacters() {
		// 한글 1자 = UTF-8 3바이트. 한도 65536은 3의 배수가 아니므로 마지막 글자가 경계에 걸림
		String body = "가".repeat(30_000);

		TestResult result = TestResult.responded(run, testCase, exchange(body, Map.of()), List.of());

		assertThat(result.isResponseBodyTruncated()).isTrue();
		assertThat(result.getResponseBody()).hasSize(65536 / 3).matches("^가+$");
	}

	@Test
	void masksCredentialsInStoredHeadersOnly() {
		ResolvedRequest request = new ResolvedRequest(HttpMethod.GET, java.net.URI.create("https://gw/a"),
				Map.of("authorization", List.of("Bearer secret"), "X-Trace", List.of("t1")), null);
		HttpExchange exchange = new HttpExchange(request, 200, Map.of("Set-Cookie", List.of("sid=secret")), "",
				Duration.ofMillis(3));

		TestResult result = TestResult.responded(run, testCase, exchange, List.of());

		assertThat(result.getRequestHeaders()).containsEntry("authorization", List.of(SensitiveHeaders.MASK))
				.containsEntry("X-Trace", List.of("t1"));
		assertThat(result.getResponseHeaders()).containsEntry("Set-Cookie", List.of(SensitiveHeaders.MASK));
		assertThat(request.headers().get("Authorization")).containsExactly("Bearer secret");
	}

	@Test
	void derivesVerdictFromAssertionResults() {
		HttpExchange exchange = exchange("", Map.of());
		AssertionResult pass = new AssertionResult("status in [2xx]", true, "was 200");
		AssertionResult fail = new AssertionResult("response time <= 10ms", false, "was 30ms");

		assertThat(TestResult.responded(run, testCase, exchange, List.of(pass)).getVerdict())
				.isEqualTo(TestResult.Verdict.PASSED);
		assertThat(TestResult.responded(run, testCase, exchange, List.of(pass, fail)).getVerdict())
				.isEqualTo(TestResult.Verdict.FAILED);
		assertThat(TestResult.failed(run, testCase, HttpMethod.GET, TestResult.ErrorReason.TIMEOUT, "t", null, null)
				.getVerdict()).isEqualTo(TestResult.Verdict.ERROR);
	}

	@Test
	void recordsFailureBeforeSendingWithoutRequestSnapshot() {
		TestResult result = TestResult.failed(run, testCase, HttpMethod.POST,
				TestResult.ErrorReason.UNRESOLVED_VARIABLE, "Unresolved variables: token", null, null);

		assertThat(result.getOutcome()).isEqualTo(TestResult.Outcome.ERROR);
		assertThat(result.getRequestMethod()).isEqualTo(HttpMethod.POST);
		assertThat(result.getRequestUrl()).isNull();
		assertThat(result.getElapsedMs()).isNull();
	}

	@Test
	void readsTheSchemaOnlyFromJsonResponses() {
		assertThat(TestResult.responded(run, testCase,
				exchange("{\"id\": 1}", Map.of("Content-Type", List.of("application/json"))), List.of())
			.getResponseSchema()).isNotNull();
		// Content-Type이 JSON이 아니면 본문이 JSON처럼 보여도 지문 없음, 없으면 본문 모양으로 판단
		assertThat(TestResult.responded(run, testCase,
				exchange("{\"id\": 1}", Map.of("Content-Type", List.of("text/plain"))), List.of())
			.getResponseSchema()).isNull();
		assertThat(TestResult.responded(run, testCase, exchange("{\"id\": 1}", Map.of()), List.of())
			.getResponseSchema()).isNotNull();
	}

	@Test
	void masksSecretsInAssertionMessages() {
		AssertionResult leaked = new AssertionResult("$.token exists", false, "was \"s3cret\"");

		TestResult result = TestResult.responded(run, testCase, exchange("", Map.of()), List.of(leaked),
				java.util.Set.of("s3cret"));

		assertThat(result.getAssertionResults()).singleElement()
			.satisfies(assertion -> assertThat(assertion.message()).isEqualTo("was \"" + SensitiveHeaders.MASK + "\""));
	}

	@Test
	void keepsErrorMessagesAndSentRequestsUpToTheirLimits() {
		ResolvedRequest sent = new ResolvedRequest(HttpMethod.POST, java.net.URI.create("https://gw/a"), Map.of(),
				"{}");

		TestResult exact = TestResult.failed(run, testCase, HttpMethod.POST, TestResult.ErrorReason.TIMEOUT,
				"m".repeat(1000), sent, Duration.ofSeconds(5));
		assertThat(exact.getErrorMessage()).hasSize(1000);
		assertThat(exact.getRequestUrl()).isEqualTo("https://gw/a");
		assertThat(exact.getRequestBody()).isEqualTo("{}");
		assertThat(exact.getElapsedMs()).isEqualTo(5000);

		TestResult over = TestResult.failed(run, testCase, HttpMethod.POST, TestResult.ErrorReason.TIMEOUT,
				"m".repeat(1001), null, null);
		assertThat(over.getErrorMessage()).isEqualTo("m".repeat(1000));

		assertThat(TestResult.failed(run, testCase, HttpMethod.GET, TestResult.ErrorReason.TIMEOUT, null, null, null)
			.getErrorMessage()).isNull();
	}

	private HttpExchange exchange(String body, Map<String, List<String>> headers) {
		return new HttpExchange(ResolvedRequest.of(HttpMethod.GET, "https://gw/a"), 200, headers, body,
				Duration.ofMillis(5));
	}

	@Test
	void holdsOnlyFingerprintedResultsForApproval() {
		TestResult json = TestResult.responded(run, testCase,
				exchange("{\"id\": 1}", Map.of("Content-Type", List.of("application/json"))), List.of());
		TestResult text = TestResult.responded(run, testCase, exchange("OK", Map.of()), List.of());

		json.holdSchema();

		assertThat(json.isSchemaPending()).isTrue();
		assertThatThrownBy(text::holdSchema).isInstanceOf(IllegalStateException.class);
		assertThat(text.isSchemaPending()).isFalse();
	}

	@Test
	void recordsHowManyTimesTheRequestWasSentAgain() {
		TestResult result = TestResult.responded(run, testCase, exchange("{}", Map.of()), List.of());
		assertThat(result.getRetryCount()).isZero();

		result.recordRetries(2);

		assertThat(result.getRetryCount()).isEqualTo(2);
		result.recordRetries(0);
		assertThat(result.getRetryCount()).isZero();
		assertThatThrownBy(() -> result.recordRetries(-1)).isInstanceOf(IllegalArgumentException.class);
	}

}
