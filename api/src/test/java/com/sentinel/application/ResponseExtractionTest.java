package com.sentinel.application;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.sentinel.TestcontainersConfiguration;
import com.sentinel.domain.Environment;
import com.sentinel.domain.Project;
import com.sentinel.domain.TestCase;
import com.sentinel.domain.TestResult.ErrorReason;
import com.sentinel.domain.TestResult.Verdict;
import com.sentinel.domain.TestSuite;
import com.sentinel.domain.TestRun;
import com.sentinel.runner.HttpExchange;
import com.sentinel.runner.HttpMethod;
import com.sentinel.runner.RequestExecutionException;
import com.sentinel.runner.ResolvedRequest;
import com.sentinel.runner.assertion.AssertionResult;
import com.sentinel.runner.assertion.JsonPathAssertion;
import com.sentinel.runner.assertion.Operator;
import com.sentinel.runner.extract.Extractor;
import com.sentinel.runner.extract.Extractor.Source;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/** 스위트 실행 안에서 앞 케이스의 응답 값을 뒤 케이스에 전달 */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, FakeRequestExecutorConfiguration.class, TestFixtures.class })
class ResponseExtractionTest {

	private static final String TOKEN = "tok-12345";

	private static final Extractor TOKEN_FROM_LOGIN = new Extractor("token", Source.BODY, "$.data.token", true);

	@Autowired
	private TestRunService service;

	@Autowired
	private FakeRequestExecutor executor;

	@Autowired
	private TestFixtures fixtures;

	private TestSuite suite;

	private long tester;

	@BeforeEach
	void setUp() {
		suite = fixtures.suite(fixtures.project());
		tester = fixtures.user().getId();
		// 로그인은 토큰, 주문 등록은 Location 헤더와 ID, 그 외는 빈 JSON
		executor.respondWith(request -> switch (request.uri().getPath()) {
			case "/login" -> exchange(request, Map.of(), "{\"data\": {\"token\": \"" + TOKEN + "\"}}");
			case "/orders" -> exchange(request, Map.of("Location", List.of("/orders/77")), "{\"id\": 77}");
			default -> exchange(request, Map.of(), "{}");
		});
	}

	@AfterEach
	void cleanUp() {
		fixtures.deleteAll();
		executor.reset();
	}

	@Test
	void passesExtractedValuesToLaterCasesOfTheRun() {
		// 환경 변수에 같은 이름이 있어도 추출한 값 우선
		Environment environment = fixtures.environment(suite.getProject(), Map.of("orderId", "stale"));
		caseOf(HttpMethod.POST, "/login", Map.of(), List.of(TOKEN_FROM_LOGIN));
		caseOf(HttpMethod.POST, "/orders", Map.of("X-Token", List.of("{{token}}")),
				List.of(new Extractor("orderId", Source.BODY, "$.id"),
						new Extractor("orderUrl", Source.HEADER, "location")));
		caseOf(HttpMethod.GET, "{{orderUrl}}/items?owner={{orderId}}", Map.of(), List.of());

		TestRunDetail run = service.getRun(runSuite(environment));

		List<ResolvedRequest> sent = executor.requests();
		assertThat(sent.get(1).headers().get("X-Token")).containsExactly(TOKEN);
		assertThat(sent.get(2).uri()).hasToString("https://gw.dev.internal/orders/77/items?owner=77");
		assertThat(run.results()).extracting(TestRunDetail.Result::verdict).containsOnly(Verdict.PASSED);
		// 추출 결과는 판정 결과 뒤에 덧붙임
		assertThat(run.results().getFirst().assertionResults()).containsExactly(
				new AssertionResult("status in [2xx]", true, "was 200"),
				new AssertionResult("extract {{token}} from $.data.token", true, "extracted (hidden)"));
		assertThat(run.results().get(1).assertionResults()).extracting(AssertionResult::message)
			.containsExactly("was 200", "was \"77\"", "was \"/orders/77\"");
	}

	@Test
	void hidesSecretExtractedValuesInTheHistory() {
		Environment environment = fixtures.environment(suite.getProject(), Map.of());
		fixtures.testCase(suite, HttpMethod.POST, "/login", null, null,
				List.of(new JsonPathAssertion("$.data.token", Operator.EQUALS, "other")), List.of(TOKEN_FROM_LOGIN));
		caseOf(HttpMethod.POST, "/orders?token={{token}}", Map.of("X-Token", List.of("{{token}}")), List.of());

		TestRunDetail run = service.getRun(runSuite(environment));

		TestRunDetail.Result login = run.results().getFirst();
		assertThat(login.response().body()).isEqualTo("{\"data\": {\"token\": \"****\"}}");
		// 판정 메시지의 실제 값도 가림
		assertThat(login.assertionResults().getFirst().message()).isEqualTo("was \"****\"");
		TestRunDetail.Result order = run.results().get(1);
		assertThat(order.request().url()).isEqualTo("https://gw.dev.internal/orders?token=****");
		assertThat(order.request().headers().get("X-Token")).containsExactly("****");
		// 실제 전송 값은 가리지 않음
		assertThat(executor.requests().get(1).headers().get("X-Token")).containsExactly(TOKEN);
	}

	@Test
	void failsTheCaseAndClearsTheVariableWhenExtractionFails() {
		// 추출 실패 시 환경 변수의 같은 이름 값으로 조용히 실행되지 않음
		Environment environment = fixtures.environment(suite.getProject(), Map.of("token", "env-token"));
		caseOf(HttpMethod.POST, "/login", Map.of(), List.of(new Extractor("token", Source.BODY, "$.missing")));
		caseOf(HttpMethod.GET, "/me", Map.of("X-Token", List.of("{{token}}")), List.of());

		TestRunDetail run = service.getRun(runSuite(environment));

		TestRunDetail.Result login = run.results().getFirst();
		assertThat(login.verdict()).isEqualTo(Verdict.FAILED);
		assertThat(login.assertionResults()).last()
			.isEqualTo(new AssertionResult("extract {{token}} from $.missing", false, "path not found"));
		TestRunDetail.Result me = run.results().get(1);
		assertThat(me.errorReason()).isEqualTo(ErrorReason.UNRESOLVED_VARIABLE);
		assertThat(me.errorMessage()).contains("Extracted by a case in this suite: token");
		assertThat(executor.requests()).hasSize(1);
	}

	@Test
	void clearsTheVariableWhenTheExtractingCaseGetsNoResponse() {
		Environment environment = fixtures.environment(suite.getProject(), Map.of("token", "env-token"));
		caseOf(HttpMethod.POST, "/login", Map.of(), List.of(TOKEN_FROM_LOGIN));
		caseOf(HttpMethod.GET, "/me", Map.of("X-Token", List.of("{{token}}")), List.of());
		executor.respondWith(request -> {
			throw new RequestExecutionException(RequestExecutionException.Reason.TIMEOUT, request,
					Duration.ofSeconds(10), null);
		});

		TestRunDetail run = service.getRun(runSuite(environment));

		assertThat(run.results()).extracting(TestRunDetail.Result::errorReason)
			.containsExactly(ErrorReason.TIMEOUT, ErrorReason.UNRESOLVED_VARIABLE);
	}

	@Test
	void explainsExtractedVariablesInASingleCaseRun() {
		Environment environment = fixtures.environment(suite.getProject(), Map.of());
		caseOf(HttpMethod.POST, "/login", Map.of(), List.of(TOKEN_FROM_LOGIN));
		TestCase me = caseOf(HttpMethod.GET, "/me/{{userId}}", Map.of("X-Token", List.of("{{token}}")), List.of());

		TestRunDetail.Result result = service.getRun(service.runTestCase(me.getId(), environment.getId(), tester))
			.results()
			.getFirst();

		assertThat(result.errorReason()).isEqualTo(ErrorReason.UNRESOLVED_VARIABLE);
		// 다른 케이스가 추출하는 변수만 안내 (userId는 어디서도 추출하지 않음)
		assertThat(result.errorMessage()).isEqualTo("Unresolved variables: userId, token. "
				+ "Extracted by a case in this suite: token (set only when the extracting case runs earlier in the"
				+ " same run: run the whole suite or run through this case)");
	}

	@Test
	void runsTheSuiteThroughTheChosenCaseWithValuesFromEarlierCases() {
		Environment environment = fixtures.environment(suite.getProject(), Map.of());
		caseOf(HttpMethod.POST, "/login", Map.of(), List.of(TOKEN_FROM_LOGIN));
		TestCase me = caseOf(HttpMethod.GET, "/me", Map.of("X-Token", List.of("{{token}}")), List.of());
		caseOf(HttpMethod.DELETE, "/orders", Map.of(), List.of());

		TestRunDetail run = service.getRun(service.runThroughCase(me.getId(), environment.getId(), tester));

		// 뒤 케이스(주문 삭제)는 보내지 않음
		assertThat(executor.requests()).extracting(request -> request.uri().getPath()).containsExactly("/login", "/me");
		assertThat(executor.requests().get(1).headers().get("X-Token")).containsExactly(TOKEN);
		assertThat(run.results()).extracting(TestRunDetail.Result::verdict).containsOnly(Verdict.PASSED);
		assertThat(run.throughCase()).isEqualTo(new IdName(me.getId(), me.getName()));
		assertThat(run.testCase()).isNull();
		assertThat(run.trigger()).isEqualTo(TestRun.Trigger.MANUAL);
		assertThat(run.triggeredBy().id()).isEqualTo(tester);
	}

	@Test
	void runsOnlyTheFirstCaseWhenItIsTheChosenOne() {
		Environment environment = fixtures.environment(suite.getProject(), Map.of());
		TestCase login = caseOf(HttpMethod.POST, "/login", Map.of(), List.of(TOKEN_FROM_LOGIN));
		caseOf(HttpMethod.GET, "/me", Map.of(), List.of());

		service.runThroughCase(login.getId(), environment.getId(), tester);

		assertThat(executor.requests()).extracting(request -> request.uri().getPath()).containsExactly("/login");
	}

	@Test
	void keepsTheUsualMessageWhenNoCaseExtractsTheVariable() {
		Environment environment = fixtures.environment(suite.getProject(), Map.of());
		caseOf(HttpMethod.GET, "/me", Map.of("X-Token", List.of("{{token}}")), List.of());

		TestRunDetail run = service.getRun(runSuite(environment));

		assertThat(run.results().getFirst().errorMessage()).isEqualTo("Unresolved variables: token");
	}

	private TestCase caseOf(HttpMethod method, String path, Map<String, List<String>> headers,
			List<Extractor> extractors) {
		return fixtures.testCase(suite, method, path, headers, null, List.of(), extractors);
	}

	private long runSuite(Environment environment) {
		return service.runSuite(suite.getId(), environment.getId(), TestRun.Trigger.MANUAL, tester);
	}

	private static HttpExchange exchange(ResolvedRequest request, Map<String, List<String>> headers, String body) {
		return new HttpExchange(request, 200, headers, body, Duration.ofMillis(12));
	}

}
