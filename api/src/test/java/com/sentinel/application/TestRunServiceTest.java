package com.sentinel.application;

import java.util.List;
import java.util.Map;

import com.sentinel.TestcontainersConfiguration;
import com.sentinel.domain.Environment;
import com.sentinel.domain.Project;
import com.sentinel.domain.TestCase;
import com.sentinel.domain.TestResult.ErrorReason;
import com.sentinel.domain.TestResult.Outcome;
import com.sentinel.domain.TestRun;
import com.sentinel.domain.TestSuite;
import com.sentinel.runner.HttpMethod;
import com.sentinel.runner.RequestExecutionException;
import com.sentinel.runner.ResolvedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import({ TestcontainersConfiguration.class, FakeRequestExecutorConfiguration.class, TestFixtures.class })
class TestRunServiceTest {

	@Autowired
	private TestRunService service;

	@Autowired
	private FakeRequestExecutor executor;

	@Autowired
	private TestFixtures fixtures;

	@AfterEach
	void cleanUp() {
		fixtures.deleteAll();
		executor.reset();
	}

	@Test
	void sendsResolvedRequestAndRecordsResponse() {
		Project project = fixtures.project();
		Environment environment = fixtures.environment(project, Map.of("userId", "42", "token", "secret-token"));
		TestCase testCase = fixtures.testCase(project, HttpMethod.POST, "/users/{{userId}}/orders",
				Map.of("Authorization", List.of("Bearer {{token}}")), "{\"userId\": {{userId}}}");
		executor.respond(503, Map.of("Retry-After", List.of("10")), "unavailable");

		TestRunDetail run = service.getRun(service.runTestCase(testCase.getId(), environment.getId()));

		ResolvedRequest sent = executor.requests().getFirst();
		assertThat(sent.uri()).hasToString("https://gw.dev.internal/users/42/orders");
		assertThat(sent.headers().get("Authorization")).containsExactly("Bearer secret-token");
		assertThat(sent.body()).isEqualTo("{\"userId\": 42}");

		assertThat(run.status()).isEqualTo(TestRun.Status.COMPLETED);
		assertThat(run.trigger()).isEqualTo(TestRun.Trigger.MANUAL);
		assertThat(run.finishedAt()).isAfterOrEqualTo(run.startedAt());
		TestRunDetail.Result result = run.results().getFirst();
		assertThat(result.outcome()).isEqualTo(Outcome.RESPONDED);
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

		TestRunDetail run = service.getRun(service.runTestCase(testCase.getId(), environment.getId()));

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
	void recordsTransportFailureReason() {
		Project project = fixtures.project();
		Environment environment = fixtures.environment(project, Map.of());
		TestCase testCase = fixtures.testCase(project, HttpMethod.GET, "/slow", null, null);
		executor.fail(RequestExecutionException.Reason.TIMEOUT);

		TestRunDetail.Result result = service.getRun(service.runTestCase(testCase.getId(), environment.getId()))
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

		TestRunDetail run = service.getRun(service.runSuite(suite.getId(), environment.getId(), TestRun.Trigger.SCHEDULED));

		assertThat(executor.requests()).extracting(request -> request.uri().getPath()).containsExactly("/a/7", "/c");
		assertThat(run.trigger()).isEqualTo(TestRun.Trigger.SCHEDULED);
		assertThat(run.status()).isEqualTo(TestRun.Status.COMPLETED);
		assertThat(run.results()).extracting(TestRunDetail.Result::testCaseId)
			.containsExactly(first.getId(), second.getId(), third.getId());
		assertThat(run.results()).extracting(TestRunDetail.Result::outcome)
			.containsExactly(Outcome.RESPONDED, Outcome.ERROR, Outcome.RESPONDED);
		assertThat(run.summary()).isEqualTo(new TestRunDetail.Summary(3, 2, 1));
	}

	@Test
	void completesEmptySuiteWithoutResults() {
		Project project = fixtures.project();
		Environment environment = fixtures.environment(project, Map.of());
		TestSuite suite = fixtures.suite(project);

		TestRunDetail run = service.getRun(service.runSuite(suite.getId(), environment.getId(), TestRun.Trigger.MANUAL));

		assertThat(run.status()).isEqualTo(TestRun.Status.COMPLETED);
		assertThat(run.results()).isEmpty();
		assertThat(run.summary()).isEqualTo(new TestRunDetail.Summary(0, 0, 0));
	}

	@Test
	void rejectsSuiteRunWithEnvironmentFromAnotherProjectOrMissingSuite() {
		TestSuite suite = fixtures.suite(fixtures.project());
		Environment otherEnvironment = fixtures.environment(fixtures.project(), Map.of());

		assertThatThrownBy(() -> service.runSuite(suite.getId(), otherEnvironment.getId(), TestRun.Trigger.MANUAL))
				.isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> service.runSuite(-1, otherEnvironment.getId(), TestRun.Trigger.MANUAL))
				.isInstanceOf(NotFoundException.class);
	}

	@Test
	void rejectsEnvironmentFromAnotherProject() {
		Project project = fixtures.project();
		TestCase testCase = fixtures.testCase(project, HttpMethod.GET, "/a", null, null);
		Environment otherEnvironment = fixtures.environment(fixtures.project(), Map.of());

		assertThatThrownBy(() -> service.runTestCase(testCase.getId(), otherEnvironment.getId()))
				.isInstanceOf(InvalidRequestException.class);
		assertThat(executor.requests()).isEmpty();
	}

	@Test
	void rejectsMissingTestCaseOrEnvironment() {
		Project project = fixtures.project();
		Environment environment = fixtures.environment(project, Map.of());
		TestCase testCase = fixtures.testCase(project, HttpMethod.GET, "/a", null, null);

		assertThatThrownBy(() -> service.runTestCase(-1, environment.getId())).isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> service.runTestCase(testCase.getId(), -1)).isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> service.getRun(-1)).isInstanceOf(NotFoundException.class);
	}

}
