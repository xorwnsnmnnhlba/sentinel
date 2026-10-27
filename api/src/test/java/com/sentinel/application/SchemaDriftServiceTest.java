package com.sentinel.application;

import java.util.List;
import java.util.Map;

import com.sentinel.TestcontainersConfiguration;
import com.sentinel.application.drift.ResultDrift;
import com.sentinel.domain.Environment;
import com.sentinel.domain.Project;
import com.sentinel.domain.TestCase;
import com.sentinel.domain.TestResult;
import com.sentinel.domain.TestRun;
import com.sentinel.domain.TestSuite;
import com.sentinel.infra.persistence.TestResultRepository;
import com.sentinel.runner.HttpMethod;
import com.sentinel.runner.schema.SchemaDrift.Change;
import com.sentinel.runner.schema.SchemaDrift.Kind;
import com.sentinel.runner.schema.SchemaDrift.Level;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

// 쿼리 수를 세므로 같은 컨텍스트에서 쿼리를 보내는 스케줄 폴링·주간 요약 확인을 끔 (통계는 SessionFactory 전체 기준)
@SpringBootTest(properties = { "sentinel.schedule.enabled=false", "spring.jpa.properties.hibernate.generate_statistics=true",
		"sentinel.schema-drift.window=4", "sentinel.schema-drift.min-observations=2" })
@Import({ TestcontainersConfiguration.class, FakeRequestExecutorConfiguration.class, TestFixtures.class })
class SchemaDriftServiceTest {

	private static final Map<String, List<String>> JSON = Map.of("Content-Type", List.of("application/json"));

	@Autowired
	private TestRunService testRunService;

	@Autowired
	private FakeRequestExecutor executor;

	@Autowired
	private TestFixtures fixtures;

	@Autowired
	private EntityManagerFactory entityManagerFactory;

	@Autowired
	private TestResultRepository results;

	@Autowired
	private Transactions transactions;

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
	void collectsABaselineBeforeJudging() {
		TestRunDetail.Result first = runWith(dev, "{\"id\": 1, \"name\": \"a\"}");
		TestRunDetail.Result second = runWith(dev, "{\"id\": 2, \"name\": \"b\"}");
		TestRunDetail.Result third = runWith(dev, "{\"id\": 3, \"name\": \"c\"}");

		// 최소 관측 수(2) 전까지는 수집 중
		assertThat(first.drift()).isEqualTo(new ResultDrift(ResultDrift.Status.COLLECTING, 0, List.of()));
		assertThat(second.drift()).isEqualTo(new ResultDrift(ResultDrift.Status.COLLECTING, 1, List.of()));
		assertThat(third.drift()).isEqualTo(new ResultDrift(ResultDrift.Status.COMPARED, 2, List.of()));
	}

	@Test
	void reportsChangesAgainstEarlierRuns() {
		runWith(dev, "{\"id\": 1, \"name\": \"a\"}");
		runWith(dev, "{\"id\": 2, \"name\": \"b\"}");

		TestRunDetail.Result changed = runWith(dev, "{\"id\": \"3\", \"name\": null, \"tags\": []}");

		assertThat(changed.drift().status()).isEqualTo(ResultDrift.Status.COMPARED);
		assertThat(changed.drift().changes()).containsExactly(
				new Change(Level.BREAKING, Kind.TYPE_CHANGED, "$.id", List.of("number"), List.of("string")),
				new Change(Level.WARNING, Kind.NULLABLE, "$.name", List.of("string"), List.of("null")),
				new Change(Level.INFO, Kind.ADDED, "$.tags", List.of(), List.of("array")));
	}

	@Test
	void usesOnlyEarlierRunsOfTheSameEnvironmentWithinTheWindow() {
		Environment staging = fixtures.environment(project, "staging", Map.of());
		for (int i = 0; i < 5; i++) {
			runWith(dev, "{\"id\": " + i + "}");
		}
		// 다른 환경의 이력은 기준선에 쓰지 않음
		TestRunDetail.Result firstOnStaging = runWith(staging, "{\"id\": 1}");
		assertThat(firstOnStaging.drift().observations()).isZero();

		// 앞선 dev 실행 5건 중 창(4)만큼만 사용
		assertThat(runWith(dev, "{\"id\": 9}").drift().observations()).isEqualTo(4);
	}

	@Test
	void comparesWithTheMostRecentRunsInTheWindow() {
		// 창(4) 밖의 가장 오래된 실행에서만 id가 문자열
		runWith(dev, "{\"id\": \"legacy\"}");
		for (int i = 0; i < 4; i++) {
			runWith(dev, "{\"id\": " + i + "}");
		}

		TestRunDetail.Result result = runWith(dev, "{\"id\": \"legacy\"}");

		assertThat(result.drift().changes()).extracting(Change::kind).containsExactly(Kind.TYPE_CHANGED);
	}

	@Test
	void judgesAnOldRunOnlyAgainstRunsBeforeIt() {
		runWith(dev, "{\"id\": 1}");
		long oldRun = runIdWith(dev, "{\"id\": 2}");
		runWith(dev, "{\"id\": \"changed\"}");
		runWith(dev, "{\"id\": \"changed\"}");

		assertThat(result(oldRun).drift().observations()).isEqualTo(1);
	}

	@Test
	void keepsReportingABreakingChangeUntilItIsAccepted() {
		runWith(dev, "{\"id\": 1}");
		runWith(dev, "{\"id\": 2}");

		TestRunDetail.Result first = runWith(dev, "{\"id\": \"3\"}");
		TestRunDetail.Result again = runWith(dev, "{\"id\": \"4\"}");

		// 승인 대기 지문은 기준선에 넣지 않으므로 같은 변화를 다음 실행에서도 보고
		assertThat(first.drift().changes()).extracting(Change::kind).containsExactly(Kind.TYPE_CHANGED);
		assertThat(again.drift().changes()).extracting(Change::kind).containsExactly(Kind.TYPE_CHANGED);
		assertThat(again.drift().observations()).isEqualTo(2);
		assertThat(results.findById(first.id()).orElseThrow().isSchemaPending()).isTrue();
	}

	@Test
	void holdsNewNullsForApproval() {
		runWith(dev, "{\"name\": \"a\"}");
		runWith(dev, "{\"name\": \"b\"}");

		TestRunDetail.Result nulled = runWith(dev, "{\"name\": null}");

		assertThat(nulled.drift().changes()).extracting(Change::kind).containsExactly(Kind.NULLABLE);
		assertThat(results.findById(nulled.id()).orElseThrow().isSchemaPending()).isTrue();
		assertThat(runWith(dev, "{\"name\": null}").drift().changes()).extracting(Change::kind)
			.containsExactly(Kind.NULLABLE);
	}

	@Test
	void acceptsNewFieldsRightAway() {
		runWith(dev, "{\"id\": 1}");
		runWith(dev, "{\"id\": 2}");

		TestRunDetail.Result added = runWith(dev, "{\"id\": 3, \"tags\": []}");
		TestRunDetail.Result next = runWith(dev, "{\"id\": 4, \"tags\": []}");

		assertThat(added.drift().changes()).extracting(Change::kind).containsExactly(Kind.ADDED);
		assertThat(results.findById(added.id()).orElseThrow().isSchemaPending()).isFalse();
		assertThat(next.drift()).isEqualTo(new ResultDrift(ResultDrift.Status.COMPARED, 3, List.of()));
	}

	@Test
	void remembersNullsAfterTheyLeaveTheWindow() {
		runWith(dev, "{\"name\": null}");
		for (int i = 0; i < 4; i++) {
			runWith(dev, "{\"name\": \"a\"}");
		}

		// 창(4)에는 null이 없지만 받아들인 지문에서 본 null은 영구 허용
		assertThat(runWith(dev, "{\"name\": null}").drift().changes()).isEmpty();
	}

	@Test
	void remembersFieldsThatWentMissingAfterTheyLeaveTheWindow() {
		runWith(dev, "{\"id\": 1, \"note\": \"a\"}");
		runWith(dev, "{\"id\": 2}");
		for (int i = 0; i < 4; i++) {
			runWith(dev, "{\"id\": 3, \"note\": \"b\"}");
		}

		assertThat(runWith(dev, "{\"id\": 4}").drift().changes()).isEmpty();
	}

	@Test
	void stillRequiresFieldsThatAppearedLaterOnceTheWindowIsFull() {
		runWith(dev, "{\"id\": 1}");
		for (int i = 0; i < 4; i++) {
			runWith(dev, "{\"id\": 2, \"note\": \"a\"}");
		}

		// 처음 나타나기 전의 없음은 영구 허용이 아님
		assertThat(runWith(dev, "{\"id\": 3}").drift().changes()).extracting(Change::kind)
			.containsExactly(Kind.REMOVED);
	}

	@Test
	void judgesAnOldRunWithoutTolerancesRecordedAfterIt() {
		runWith(dev, "{\"name\": \"a\"}");
		runWith(dev, "{\"name\": \"b\"}");
		long oldRun = runIdWith(dev, "{\"name\": null}");
		long oldResult = result(oldRun).id();
		// 이 결과 뒤에 기록된 허용 (이 결과를 승인한 경우와 같음)
		transactions.writeWithoutResult(
				status -> results.addSchemaTolerance(orders.getId(), dev.getId(), "$.name", "NULLABLE", oldResult));

		assertThat(result(oldRun).drift().changes()).extracting(Change::kind).containsExactly(Kind.NULLABLE);
		assertThat(runWith(dev, "{\"name\": null}").drift().changes()).isEmpty();
	}

	@Test
	void keepsVeryLongPathsOnlyWithinTheWindow() {
		// 영구 허용 표의 경로 길이(1000자)를 넘는 경로는 남기지 않음. 넘는 값을 넣으려다 결과 저장이 실패하면 안 됨
		String key = "k".repeat(1000);
		TestRunDetail.Result result = runWith(dev, "{\"" + key + "\": null}");

		assertThat(result.verdict()).isEqualTo(TestResult.Verdict.PASSED);
		assertThat(result.drift().status()).isEqualTo(ResultDrift.Status.COLLECTING);
	}

	@Test
	void leavesResultsWithoutAFingerprintAlone() {
		executor.respond(200, Map.of("Content-Type", List.of("text/plain")), "OK");

		TestRunDetail.Result result = result(testRunService.runTestCase(orders.getId(), dev.getId(), tester));

		assertThat(result.responseSchema()).isNull();
		assertThat(result.drift()).isNull();
	}

	@Test
	void usesTheSameNumberOfQueriesForAnyNumberOfCases() {
		TestSuite many = orders.getSuite();
		for (int i = 0; i < 3; i++) {
			fixtures.testCase(many, HttpMethod.GET, "/other-" + i, null, null);
		}
		executor.respond(200, JSON, "{\"id\": 1}");
		long manyCases = runRepeatedly(many);
		long oneCase = runRepeatedly(fixtures.testCase(project, HttpMethod.GET, "/single", null, null).getSuite());

		assertThat(testRunService.getRun(manyCases).results()).hasSize(4)
			.allSatisfy(result -> assertThat(result.drift().status()).isEqualTo(ResultDrift.Status.COMPARED));
		// 케이스 수와 무관하게 이전 지문은 쿼리 1회
		assertThat(queriesToLoad(manyCases)).isEqualTo(queriesToLoad(oneCase));
	}

	/** 스위트를 여러 번 실행하고 마지막 실행 ID 반환 */
	private long runRepeatedly(TestSuite suite) {
		long last = 0;
		for (int i = 0; i < 3; i++) {
			last = testRunService.runSuite(suite.getId(), dev.getId(), TestRun.Trigger.MANUAL, tester);
		}
		return last;
	}

	private long queriesToLoad(long runId) {
		Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		statistics.clear();
		testRunService.getRun(runId);
		return statistics.getPrepareStatementCount();
	}

	private TestRunDetail.Result runWith(Environment environment, String body) {
		return result(runIdWith(environment, body));
	}

	private long runIdWith(Environment environment, String body) {
		executor.respond(200, JSON, body);
		return testRunService.runTestCase(orders.getId(), environment.getId(), tester);
	}

	private TestRunDetail.Result result(long runId) {
		return testRunService.getRun(runId).results().getFirst();
	}

}
