package com.sentinel.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.sentinel.TestcontainersConfiguration;
import com.sentinel.domain.Environment;
import com.sentinel.domain.Project;
import com.sentinel.domain.TestCase;
import com.sentinel.domain.TestResult;
import com.sentinel.domain.TestResult.ErrorReason;
import com.sentinel.domain.TestResult.Outcome;
import com.sentinel.domain.TestResult.Verdict;
import com.sentinel.domain.TestRun;
import com.sentinel.domain.TestSuite;
import com.sentinel.domain.User;
import com.sentinel.infra.persistence.TestCaseRepository;
import com.sentinel.infra.persistence.UserRepository;
import com.sentinel.runner.HttpMethod;
import com.sentinel.runner.RequestExecutionException;
import com.sentinel.runner.ResolvedRequest;
import com.sentinel.runner.assertion.JsonPathAssertion;
import com.sentinel.runner.assertion.Operator;
import com.sentinel.runner.assertion.ResponseTimeAssertion;
import com.sentinel.runner.assertion.StatusCodeAssertion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
@Import({ TestcontainersConfiguration.class, FakeRequestExecutorConfiguration.class, TestFixtures.class })
class TestRunServiceTest {

	@Autowired
	private TestRunService service;

	@Autowired
	private FakeRequestExecutor executor;

	@Autowired
	private TestFixtures fixtures;

	@Autowired
	private UserRepository users;

	@Autowired
	private TestCaseRepository testCases;

	@AfterEach
	void cleanUp() {
		fixtures.deleteAll();
		testerId = null;
		executor.reset();
	}

	@Test
	void sendsResolvedRequestAndRecordsResponse() {
		Project project = fixtures.project();
		Environment environment = fixtures.environment(project, Map.of("userId", "42", "token", "secret-token"));
		TestCase testCase = fixtures.testCase(project, HttpMethod.POST, "/users/{{userId}}/orders",
				Map.of("Authorization", List.of("Bearer {{token}}")), "{\"userId\": {{userId}}}");
		executor.respond(503, Map.of("Retry-After", List.of("10")), "unavailable");

		TestRunDetail run = service.getRun(service.runTestCase(testCase.getId(), environment.getId(), tester()));

		ResolvedRequest sent = executor.requests().getFirst();
		assertThat(sent.uri()).hasToString("https://gw.dev.internal/users/42/orders");
		assertThat(sent.headers().get("Authorization")).containsExactly("Bearer secret-token");
		assertThat(sent.body()).isEqualTo("{\"userId\": 42}");

		assertThat(run.status()).isEqualTo(TestRun.Status.COMPLETED);
		assertThat(run.trigger()).isEqualTo(TestRun.Trigger.MANUAL);
		assertThat(run.finishedAt()).isAfterOrEqualTo(run.startedAt());
		TestRunDetail.Result result = run.results().getFirst();
		assertThat(result.outcome()).isEqualTo(Outcome.RESPONDED);
		assertThat(result.verdict()).isEqualTo(Verdict.FAILED);
		assertThat(result.assertionResults()).singleElement()
			.satisfies(assertion -> assertThat(assertion.description()).isEqualTo("status in [2xx]"));
		assertThat(result.response().statusCode()).isEqualTo(503);
		assertThat(result.response().body()).isEqualTo("unavailable");
		assertThat(result.request().url()).isEqualTo("https://gw.dev.internal/users/42/orders");
		assertThat(result.request().headers().get("Authorization")).containsExactly("****");
		assertThat(result.elapsedMs()).isEqualTo(12);
	}

	@Test
	void recordsUnresolvedVariablesWithoutSending() {
		Project project = fixtures.project();
		Environment environment = fixtures.environment(project, Map.of());
		TestCase testCase = fixtures.testCase(project, HttpMethod.GET, "/users/{{userId}}",
				Map.of("Authorization", List.of("Bearer {{token}}")), null);

		TestRunDetail run = service.getRun(service.runTestCase(testCase.getId(), environment.getId(), tester()));

		assertThat(executor.requests()).isEmpty();
		assertThat(run.status()).isEqualTo(TestRun.Status.COMPLETED);
		TestRunDetail.Result result = run.results().getFirst();
		assertThat(result.outcome()).isEqualTo(Outcome.ERROR);
		assertThat(result.errorReason()).isEqualTo(ErrorReason.UNRESOLVED_VARIABLE);
		assertThat(result.errorMessage()).contains("userId", "token");
		assertThat(result.request().method()).isEqualTo(HttpMethod.GET);
		assertThat(result.request().url()).isNull();
		assertThat(result.response()).isNull();
	}

	@Test
	void resolvesPathStartingWithVariable() {
		Project project = fixtures.project();
		Environment environment = fixtures.environment(project, Map.of("prefix", "/billing/v1"));
		TestCase testCase = fixtures.testCase(project, HttpMethod.GET, "{{prefix}}/history", null, null);

		service.runTestCase(testCase.getId(), environment.getId(), tester());

		assertThat(executor.requests().getFirst().uri()).hasToString("https://gw.dev.internal/billing/v1/history");
	}

	@Test
	void recordsInvalidRequestWhenVariablePathDoesNotStartWithSlash() {
		Project project = fixtures.project();
		Environment environment = fixtures.environment(project, Map.of("prefix", ".evil.example"));
		TestCase testCase = fixtures.testCase(project, HttpMethod.GET, "{{prefix}}/history", null, null);

		TestRunDetail.Result result = service.getRun(service.runTestCase(testCase.getId(), environment.getId(), tester()))
			.results()
			.getFirst();

		assertThat(executor.requests()).isEmpty();
		assertThat(result.outcome()).isEqualTo(Outcome.ERROR);
		assertThat(result.errorReason()).isEqualTo(ErrorReason.INVALID_REQUEST);
		assertThat(result.errorMessage()).contains("after variable substitution");
	}

	@Test
	void recordsWhoStartedAManualRun() {
		Project project = fixtures.project();
		Environment environment = fixtures.environment(project, Map.of());
		TestCase testCase = fixtures.testCase(project, HttpMethod.GET, "/a", null, null);
		User user = fixtures.user();

		TestRunDetail run = service.getRun(service.runTestCase(testCase.getId(), environment.getId(), user.getId()));

		assertThat(run.triggeredBy()).isNotNull();
		assertThat(run.triggeredBy().id()).isEqualTo(user.getId());
		assertThat(run.triggeredBy().name()).isEqualTo(user.getEmail());
	}

	@Test
	void fingerprintsTheWholeJsonBodyEvenWhenTheStoredBodyIsTruncated() {
		Project project = fixtures.project();
		Environment environment = fixtures.environment(project, Map.of());
		TestCase testCase = fixtures.testCase(project, HttpMethod.GET, "/orders", null, null);
		// 저장 한도(64KB)를 넘겨 본문은 잘리지만 뒤쪽 필드까지 지문에 포함
		String padding = "x".repeat(TestResult.MAX_BODY_BYTES);
		executor.respond(200, Map.of("content-type", List.of("application/json; charset=UTF-8")),
				"{\"padding\": \"" + padding + "\", \"tail\": {\"id\": 1, \"tags\": [\"a\", null]}}");

		TestRunDetail.Result result = service.getRun(service.runTestCase(testCase.getId(), environment.getId(), tester()))
			.results()
			.getFirst();

		assertThat(result.response().bodyTruncated()).isTrue();
		assertThat(result.response().body()).doesNotContain("tail");
		assertThat(result.responseSchema().fields()).containsExactly(Map.entry("$", List.of("object")),
				Map.entry("$.padding", List.of("string")), Map.entry("$.tail", List.of("object")),
				Map.entry("$.tail.id", List.of("number")), Map.entry("$.tail.tags", List.of("array")),
				Map.entry("$.tail.tags[]", List.of("null", "string")));
		assertThat(result.responseSchema().complete()).isTrue();
	}

	@Test
	void keepsNoFingerprintForNonJsonResponsesOrMissingResponses() {
		Project project = fixtures.project();
		Environment environment = fixtures.environment(project, Map.of());
		TestCase html = fixtures.testCase(project, HttpMethod.GET, "/page", null, null);
		TestCase unresolved = fixtures.testCase(html.getSuite(), HttpMethod.GET, "/{{missing}}", null, null);
		executor.respond(200, Map.of("Content-Type", List.of("text/html")), "<html>{}</html>");

		TestRunDetail run = service.getRun(service.runSuite(html.getSuite().getId(), environment.getId(),
				TestRun.Trigger.MANUAL, tester()));

		assertThat(run.results()).extracting(TestRunDetail.Result::testCaseId)
			.containsExactly(html.getId(), unresolved.getId());
		assertThat(run.results()).allSatisfy(result -> assertThat(result.responseSchema()).isNull());
	}

	@Test
	void recordsTheTargetCaseOfASingleCaseRun() {
		Project project = fixtures.project();
		Environment environment = fixtures.environment(project, Map.of());
		TestCase target = fixtures.testCase(project, HttpMethod.GET, "/a", null, null);
		fixtures.testCase(target.getSuite(), HttpMethod.GET, "/b", null, null);

		TestRunDetail single = service.getRun(service.runTestCase(target.getId(), environment.getId(), tester()));
		TestRunDetail whole = service.getRun(service.runSuite(target.getSuite().getId(), environment.getId(),
				TestRun.Trigger.MANUAL, tester()));

		assertThat(single.testCase()).isEqualTo(new IdName(target.getId(), target.getName()));
		assertThat(single.results()).singleElement()
			.satisfies(result -> assertThat(result.testCaseId()).isEqualTo(target.getId()));
		// 같은 스위트의 실행이지만 스위트 전체 실행은 대상 케이스 없음
		assertThat(single.suiteId()).isEqualTo(whole.suiteId());
		assertThat(whole.testCase()).isNull();
		assertThat(whole.results()).hasSize(2);

		// 케이스를 삭제(보관)해도 이력에는 이름 유지
		target.archive(Instant.now());
		testCases.saveAndFlush(target);
		assertThat(service.getRun(single.id()).testCase().name()).isEqualTo(target.getName());
	}

	@Test
	void keepsTheRunHistoryOfDeletedUsers() {
		Project project = fixtures.project();
		Environment environment = fixtures.environment(project, Map.of());
		TestCase testCase = fixtures.testCase(project, HttpMethod.GET, "/a", null, null);
		User user = fixtures.user();
		long runId = service.runTestCase(testCase.getId(), environment.getId(), user.getId());

		user.archive(Instant.now());
		users.saveAndFlush(user);

		// 보관된 사용자도 이력에는 그대로 표시
		assertThat(service.getRun(runId).triggeredBy().name()).isEqualTo(user.getEmail());
	}

	@Test
	void hasNoTriggeringUserForScheduledRuns() {
		Project project = fixtures.project();
		Environment environment = fixtures.environment(project, Map.of());
		TestSuite suite = fixtures.suite(project);
		fixtures.testCase(suite, HttpMethod.GET, "/a", null, null);

		long runId = service.runSuite(suite.getId(), environment.getId(), TestRun.Trigger.SCHEDULED, null);

		assertThat(service.getRun(runId).triggeredBy()).isNull();
	}

	@Test
	void recordsTransportFailureReason() {
		Project project = fixtures.project();
		Environment environment = fixtures.environment(project, Map.of());
		TestCase testCase = fixtures.testCase(project, HttpMethod.GET, "/slow", null, null);
		executor.fail(RequestExecutionException.Reason.TIMEOUT);

		TestRunDetail.Result result = service.getRun(service.runTestCase(testCase.getId(), environment.getId(), tester()))
			.results()
			.getFirst();

		assertThat(result.outcome()).isEqualTo(Outcome.ERROR);
		assertThat(result.errorReason()).isEqualTo(ErrorReason.TIMEOUT);
		assertThat(result.request().url()).isEqualTo("https://gw.dev.internal/slow");
		assertThat(result.elapsedMs()).isEqualTo(1000);
	}

	@Test
	void runsAllCasesOfSuiteInOrderAndKeepsGoingAfterFailures() {
		Project project = fixtures.project();
		Environment environment = fixtures.environment(project, Map.of("id", "7"));
		TestSuite suite = fixtures.suite(project);
		TestCase first = fixtures.testCase(suite, HttpMethod.GET, "/a/{{id}}", null, null);
		TestCase second = fixtures.testCase(suite, HttpMethod.GET, "/b/{{missing}}", null, null);
		TestCase third = fixtures.testCase(suite, HttpMethod.DELETE, "/c", null, null);
		fixtures.testCase(fixtures.suite(project), HttpMethod.GET, "/other-suite", null, null);

		TestRunDetail run = service.getRun(service.runSuite(suite.getId(), environment.getId(), TestRun.Trigger.SCHEDULED, tester()));

		assertThat(executor.requests()).extracting(request -> request.uri().getPath()).containsExactly("/a/7", "/c");
		assertThat(run.trigger()).isEqualTo(TestRun.Trigger.SCHEDULED);
		assertThat(run.status()).isEqualTo(TestRun.Status.COMPLETED);
		assertThat(run.results()).extracting(TestRunDetail.Result::testCaseId)
			.containsExactly(first.getId(), second.getId(), third.getId());
		assertThat(run.results()).extracting(TestRunDetail.Result::outcome)
			.containsExactly(Outcome.RESPONDED, Outcome.ERROR, Outcome.RESPONDED);
		assertThat(run.summary()).isEqualTo(new TestRunDetail.Summary(3, 2, 0, 1));
	}

	@Test
	void judgesResponsesWithCaseAssertions() {
		Project project = fixtures.project();
		Environment environment = fixtures.environment(project, Map.of());
		TestSuite suite = fixtures.suite(project);
		fixtures.testCase(suite, HttpMethod.GET, "/health", null, null,
				List.of(new StatusCodeAssertion(List.of("200")),
						new JsonPathAssertion("$.status", Operator.EQUALS, "UP")));
		fixtures.testCase(suite, HttpMethod.GET, "/slow", null, null,
				List.of(new ResponseTimeAssertion(10)));
		executor.respond(200, Map.of(), "{\"status\": \"UP\"}");

		TestRunDetail run = service.getRun(service.runSuite(suite.getId(), environment.getId(), TestRun.Trigger.MANUAL, tester()));

		assertThat(run.results()).extracting(TestRunDetail.Result::verdict).containsExactly(Verdict.PASSED, Verdict.FAILED);
		assertThat(run.results().get(1).assertionResults()).singleElement().satisfies(assertion -> {
			assertThat(assertion.passed()).isFalse();
			assertThat(assertion.description()).isEqualTo("response time <= 10ms");
			assertThat(assertion.message()).isEqualTo("was 12ms");
		});
		assertThat(run.summary()).isEqualTo(new TestRunDetail.Summary(2, 1, 1, 0));
	}

	@Test
	void logsOneSummaryLinePerCompletedRun(CapturedOutput output) {
		Project project = fixtures.project();
		Environment environment = fixtures.environment(project, Map.of());
		TestSuite suite = fixtures.suite(project);
		fixtures.testCase(suite, HttpMethod.GET, "/ok", null, null);
		fixtures.testCase(suite, HttpMethod.GET, "/fail", null, null, List.of(new StatusCodeAssertion(List.of("201"))));
		fixtures.testCase(suite, HttpMethod.GET, "/{{missing}}", null, null);

		long runId = service.runSuite(suite.getId(), environment.getId(), TestRun.Trigger.SCHEDULED, tester());

		assertThat(output).containsPattern("Run " + runId + " completed: trigger=SCHEDULED suite=" + suite.getId()
				+ " total=3 passed=1 failed=1 errors=1 elapsed=\\d+ms");
	}

	@Test
	void completesEmptySuiteWithoutResults() {
		Project project = fixtures.project();
		Environment environment = fixtures.environment(project, Map.of());
		TestSuite suite = fixtures.suite(project);

		TestRunDetail run = service.getRun(service.runSuite(suite.getId(), environment.getId(), TestRun.Trigger.MANUAL, tester()));

		assertThat(run.status()).isEqualTo(TestRun.Status.COMPLETED);
		assertThat(run.results()).isEmpty();
		assertThat(run.summary()).isEqualTo(new TestRunDetail.Summary(0, 0, 0, 0));
	}

	@Test
	void rejectsSuiteRunWithEnvironmentFromAnotherProjectOrMissingSuite() {
		TestSuite suite = fixtures.suite(fixtures.project());
		Environment otherEnvironment = fixtures.environment(fixtures.project(), Map.of());

		assertThatThrownBy(() -> service.runSuite(suite.getId(), otherEnvironment.getId(), TestRun.Trigger.MANUAL, tester()))
				.isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> service.runSuite(-1, otherEnvironment.getId(), TestRun.Trigger.MANUAL, tester()))
				.isInstanceOf(NotFoundException.class);
	}

	@Test
	void rejectsEnvironmentFromAnotherProject() {
		Project project = fixtures.project();
		TestCase testCase = fixtures.testCase(project, HttpMethod.GET, "/a", null, null);
		Environment otherEnvironment = fixtures.environment(fixtures.project(), Map.of());

		assertThatThrownBy(() -> service.runTestCase(testCase.getId(), otherEnvironment.getId(), tester()))
				.isInstanceOf(InvalidRequestException.class);
		assertThat(executor.requests()).isEmpty();
	}

	@Test
	void rejectsMissingTestCaseOrEnvironment() {
		Project project = fixtures.project();
		Environment environment = fixtures.environment(project, Map.of());
		TestCase testCase = fixtures.testCase(project, HttpMethod.GET, "/a", null, null);

		assertThatThrownBy(() -> service.runTestCase(-1, environment.getId(), tester())).isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> service.runTestCase(testCase.getId(), -1, tester())).isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> service.getRun(-1)).isInstanceOf(NotFoundException.class);
	}


	/** 수동 실행 주체. 실행마다 같은 계정을 사용하도록 한 번만 생성 */
	private long tester() {
		if (testerId == null) {
			testerId = fixtures.user().getId();
		}
		return testerId;
	}

	private Long testerId;

}
