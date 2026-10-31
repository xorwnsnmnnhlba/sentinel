package com.sentinel.application;

import java.util.List;
import java.util.Map;

import com.sentinel.TestcontainersConfiguration;
import com.sentinel.application.PendingSchemaChangeService.PendingSchemaChange;
import com.sentinel.domain.Environment;
import com.sentinel.domain.Project;
import com.sentinel.domain.TestCase;
import com.sentinel.runner.HttpMethod;
import com.sentinel.runner.schema.SchemaDrift.Change;
import com.sentinel.runner.schema.SchemaDrift.Kind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = { "sentinel.schedule.enabled=false", "sentinel.schema-drift.window=4",
		"sentinel.schema-drift.min-observations=2" })
@Import({ TestcontainersConfiguration.class, FakeRequestExecutorConfiguration.class, TestFixtures.class })
class PendingSchemaChangeServiceTest {

	private static final Map<String, List<String>> JSON = Map.of("Content-Type", List.of("application/json"));

	@Autowired
	private PendingSchemaChangeService service;

	@Autowired
	private TestRunService testRunService;

	@Autowired
	private CatalogCommandService catalog;

	@Autowired
	private FakeRequestExecutor executor;

	@Autowired
	private TestFixtures fixtures;

	private Project project;

	private Environment dev;

	private TestCase orders;

	private long tester;

	@BeforeEach
	void setUp() {
		project = fixtures.project();
		dev = fixtures.environment(project, "dev", Map.of());
		orders = fixtures.testCase(project, HttpMethod.GET, "/orders", null, null);
		tester = fixtures.user().getId();
	}

	@AfterEach
	void cleanUp() {
		fixtures.deleteAll();
		executor.reset();
	}

	@Test
	void listsTheLatestPendingResultOfEachCaseAndEnvironment() {
		collectBaseline(orders, dev);
		run(orders, dev, "{\"id\": \"3\"}");
		long latest = run(orders, dev, "{\"id\": \"4\"}");

		List<PendingSchemaChange> pending = service.list();

		assertThat(pending).singleElement().satisfies(change -> {
			assertThat(change.id()).isEqualTo(resultOf(latest));
			assertThat(change.run().id()).isEqualTo(latest);
			assertThat(change.run().startedAt()).isNotNull();
			assertThat(change.testCase()).isEqualTo(new IdName(orders.getId(), orders.getName()));
			assertThat(change.suite().id()).isEqualTo(orders.getSuite().getId());
			assertThat(change.project()).isEqualTo(new IdName(project.getId(), project.getName()));
			assertThat(change.environment()).isEqualTo(new IdName(dev.getId(), "dev"));
			assertThat(change.changes()).extracting(Change::kind).containsExactly(Kind.TYPE_CHANGED);
		});
	}

	@Test
	void dropsPendingResultsFollowedByAnAcceptedOne() {
		collectBaseline(orders, dev);
		// 한 번만 다른 구조(일시적인 오류 응답 등)였다가 원래대로
		run(orders, dev, "{\"error\": \"busy\"}");
		run(orders, dev, "{\"id\": 5}");

		assertThat(service.list()).isEmpty();
	}

	@Test
	void keepsPendingResultsFollowedOnlyByResponsesWithoutAFingerprint() {
		collectBaseline(orders, dev);
		long changed = run(orders, dev, "{\"id\": \"3\"}");
		// JSON이 아닌 응답은 구조를 받아들인 것이 아님
		executor.respond(200, Map.of("Content-Type", List.of("text/plain")), "busy");
		testRunService.runTestCase(orders.getId(), dev.getId(), tester);

		assertThat(service.list()).extracting(PendingSchemaChange::id).containsExactly(resultOf(changed));
	}

	@Test
	void dropsCasesOnceTheChangeIsApproved() {
		collectBaseline(orders, dev);
		long first = run(orders, dev, "{\"id\": \"3\"}");
		run(orders, dev, "{\"id\": \"4\"}");

		// 앞선 결과를 승인하면 마지막 결과도 반영된 기준선으로 다시 비교해 대기가 아님
		testRunService.approveSchema(first, resultOf(first), tester);

		assertThat(service.list()).isEmpty();
	}

	@Test
	void judgesEachEnvironmentApart() {
		Environment staging = fixtures.environment(project, "staging", Map.of());
		collectBaseline(orders, dev);
		collectBaseline(orders, staging);
		long changed = run(orders, dev, "{\"id\": \"3\"}");
		// 다른 환경에서 받아들인 결과는 이 환경의 대기를 지우지 않음
		run(orders, staging, "{\"id\": 3}");

		assertThat(service.list()).extracting(PendingSchemaChange::id).containsExactly(resultOf(changed));
	}

	@Test
	void ordersByTheMostSevereChangeThenByTheLatestRun() {
		TestCase users = fixtures.testCase(project, HttpMethod.GET, "/users", null, null);
		TestCase items = fixtures.testCase(project, HttpMethod.GET, "/items", null, null);
		collectBaseline(orders, dev);
		collectBaseline(users, dev);
		collectBaseline(items, dev);
		long breaking = run(orders, dev, "{\"id\": \"3\"}");
		long olderBreaking = run(items, dev, "{\"id\": \"3\"}");
		long warning = run(users, dev, "{\"id\": null}");
		long newerBreaking = run(items, dev, "{\"id\": \"4\"}");

		assertThat(service.list()).extracting(PendingSchemaChange::id)
			.containsExactly(resultOf(newerBreaking), resultOf(breaking), resultOf(warning))
			.doesNotContain(resultOf(olderBreaking));
	}

	@Test
	void leavesOutArchivedCasesAndEnvironments() {
		Environment staging = fixtures.environment(project, "staging", Map.of());
		TestCase users = fixtures.testCase(project, HttpMethod.GET, "/users", null, null);
		collectBaseline(orders, staging);
		collectBaseline(users, dev);
		run(orders, staging, "{\"id\": \"3\"}");
		run(users, dev, "{\"id\": \"3\"}");

		catalog.archiveEnvironment(staging.getId(), tester);
		catalog.archiveCase(users.getId(), tester);

		assertThat(service.list()).isEmpty();
	}

	/** 비교할 수 있도록 같은 구조의 응답을 최소 관측 수(2)만큼 */
	private void collectBaseline(TestCase testCase, Environment environment) {
		run(testCase, environment, "{\"id\": 1}");
		run(testCase, environment, "{\"id\": 2}");
	}

	/** @return 실행 ID */
	private long run(TestCase testCase, Environment environment, String body) {
		executor.respond(200, JSON, body);
		return testRunService.runTestCase(testCase.getId(), environment.getId(), tester);
	}

	private long resultOf(long runId) {
		return testRunService.getRun(runId).results().getFirst().id();
	}

}
