package com.sentinel.domain;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.sentinel.runner.HttpExchange;
import com.sentinel.runner.HttpMethod;
import com.sentinel.runner.ResolvedRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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

		TestResult result = TestResult.responded(run, testCase, exchange(body, Map.of()));

		assertThat(result.getResponseBody()).isEqualTo(body);
		assertThat(result.isResponseBodyTruncated()).isFalse();
	}

	@Test
	void truncatesBodyOverLimitWithoutSplittingMultibyteCharacters() {
		// 한글 1자 = UTF-8 3바이트. 한도 65536은 3의 배수가 아니므로 마지막 글자가 경계에 걸림
		String body = "가".repeat(30_000);

		TestResult result = TestResult.responded(run, testCase, exchange(body, Map.of()));

		assertThat(result.isResponseBodyTruncated()).isTrue();
		assertThat(result.getResponseBody()).hasSize(65536 / 3).matches("^가+$");
	}

	@Test
	void masksCredentialsInStoredHeadersOnly() {
		ResolvedRequest request = new ResolvedRequest(HttpMethod.GET, java.net.URI.create("https://gw/a"),
				Map.of("authorization", List.of("Bearer secret"), "X-Trace", List.of("t1")), null);
		HttpExchange exchange = new HttpExchange(request, 200, Map.of("Set-Cookie", List.of("sid=secret")), "",
				Duration.ofMillis(3));

		TestResult result = TestResult.responded(run, testCase, exchange);

		assertThat(result.getRequestHeaders()).containsEntry("authorization", List.of(SensitiveHeaders.MASK))
				.containsEntry("X-Trace", List.of("t1"));
		assertThat(result.getResponseHeaders()).containsEntry("Set-Cookie", List.of(SensitiveHeaders.MASK));
		assertThat(request.headers().get("Authorization")).containsExactly("Bearer secret");
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

	private HttpExchange exchange(String body, Map<String, List<String>> headers) {
		return new HttpExchange(ResolvedRequest.of(HttpMethod.GET, "https://gw/a"), 200, headers, body,
				Duration.ofMillis(5));
	}

}
