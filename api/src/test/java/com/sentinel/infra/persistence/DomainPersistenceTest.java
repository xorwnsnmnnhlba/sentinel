package com.sentinel.infra.persistence;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.sentinel.TestcontainersConfiguration;
import com.sentinel.domain.Environment;
import com.sentinel.domain.Project;
import com.sentinel.domain.RequestSpec;
import com.sentinel.domain.TestCase;
import com.sentinel.domain.TestResult;
import com.sentinel.domain.TestRun;
import com.sentinel.domain.TestSuite;
import com.sentinel.runner.HttpExchange;
import com.sentinel.runner.HttpMethod;
import com.sentinel.runner.ResolvedRequest;
import com.sentinel.runner.assertion.Assertion;
import com.sentinel.runner.assertion.AssertionResult;
import com.sentinel.runner.assertion.BodyContainsAssertion;
import com.sentinel.runner.assertion.HeaderAssertion;
import com.sentinel.runner.assertion.JsonPathAssertion;
import com.sentinel.runner.assertion.Operator;
import com.sentinel.runner.assertion.ResponseTimeAssertion;
import com.sentinel.runner.assertion.StatusCodeAssertion;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class DomainPersistenceTest {

	@Autowired
	private ProjectRepository projects;

	@Autowired
	private EnvironmentRepository environments;

	@Autowired
	private TestSuiteRepository suites;

	@Autowired
	private TestCaseRepository testCases;

	@Autowired
	private TestRunRepository runs;

	@Autowired
	private TestResultRepository results;

	@Autowired
	private EntityManager entityManager;

	@Test
	void createsTablesInSentinelSchema() {
		List<Object> schemas = entityManager.createNativeQuery("""
				SELECT DISTINCT table_schema FROM information_schema.tables
				WHERE table_name IN ('project', 'environment', 'test_suite', 'test_case')
				""").getResultList();

		assertThat(schemas).containsExactly("sentinel");
	}

	@Test
	void appliesFlywayMigrationsToSentinelSchema() {
		// Flyway가 스키마를 직접 생성하면 version NULL인 스키마 생성 기록이 함께 남으므로 제외
		List<Object> versions = entityManager.createNativeQuery("""
				SELECT version FROM sentinel.flyway_schema_history
				WHERE success AND version IS NOT NULL ORDER BY installed_rank
				""").getResultList();

		assertThat(versions).containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16", "17", "18", "19", "20", "21", "22", "23", "24", "25", "26", "27");
	}

	@Test
	void hasNoForeignKeyConstraints() {
		Object foreignKeys = entityManager.createNativeQuery("""
				SELECT count(*) FROM information_schema.table_constraints
				WHERE constraint_schema = 'sentinel' AND constraint_type = 'FOREIGN KEY'
				""").getSingleResult();

		assertThat(((Number) foreignKeys).intValue()).isZero();
	}

	@Test
	void mapsJsonColumnsToStandardJsonType() {
		List<Object> types = entityManager.createNativeQuery("""
				SELECT data_type FROM information_schema.columns
				WHERE table_schema = 'sentinel'
				  AND (table_name, column_name) IN (('environment', 'variables'), ('test_case', 'headers'))
				""").getResultList();

		assertThat(types).containsExactly("json", "json");
	}

	@Test
	void storesEnvironmentVariablesAsJson() {
		Project project = projects.save(new Project("billing", "결제 모듈"));
		Environment environment = new Environment(project, "dev", "https://gw.dev.internal");
		environment.putVariable("userId", "42");
		environment.putVariable("locale", "ko-KR");
		environments.save(environment);
		flushAndClear();

		Environment found = environments.findByProjectIdAndArchivedAtIsNullOrderByName(project.getId()).getFirst();

		assertThat(found.getBaseUrl()).isEqualTo("https://gw.dev.internal");
		assertThat(found.getVariables()).containsExactlyInAnyOrderEntriesOf(Map.of("userId", "42", "locale", "ko-KR"));
		assertThat(found.getCreatedAt()).isNotNull();
	}

	@Test
	void storesTestCaseWithRequestSpec() {
		TestSuite suite = suites.save(new TestSuite(projects.save(new Project("billing", null)), "charge", null));
		RequestSpec request = new RequestSpec(HttpMethod.POST, "/billing/v1/charge",
				Map.of("Authorization", List.of("Bearer {{token}}"), "Accept", List.of("application/json", "text/plain")),
				"{\"amount\": 1000, \"memo\": \"{{memo}}\"}");
		testCases.save(new TestCase(suite, "정상 결제", request));
		testCases.save(new TestCase(suite, "금액 누락", new RequestSpec(HttpMethod.POST, "/billing/v1/charge", null, "{}")));
		flushAndClear();

		List<TestCase> found = testCases.findActiveInRunOrder(suite.getId());

		assertThat(found).extracting(TestCase::getName).containsExactly("정상 결제", "금액 누락");
		assertThat(found.getFirst().getRequest()).isEqualTo(request);
		assertThat(found.get(1).getRequest().getHeaders()).isEmpty();
	}

	@Test
	void storesPolymorphicAssertionsAsJson() {
		TestSuite suite = suites.save(new TestSuite(projects.save(new Project("billing", null)), "charge", null));
		List<Assertion> assertions = List.of(new StatusCodeAssertion(List.of("200", "2xx")),
				new ResponseTimeAssertion(2000), new HeaderAssertion("Content-Type", Operator.CONTAINS, "json"),
				new JsonPathAssertion("$.amount", Operator.GREATER_THAN_OR_EQUAL, 1000),
				new JsonPathAssertion("$.memo", Operator.EQUALS, null), new BodyContainsAssertion("ok"));
		TestCase saved = testCases.save(new TestCase(suite, "정상 결제",
				new RequestSpec(HttpMethod.POST, "/billing/v1/charge", null, "{}"), assertions));
		flushAndClear();

		assertThat(testCases.findById(saved.getId()).orElseThrow().getAssertions()).isEqualTo(assertions);
	}

	@Test
	void readsAssertionsWrittenAsRawJsonBySql() {
		TestSuite suite = suites.save(new TestSuite(projects.save(new Project("billing", null)), "charge", null));
		TestCase saved = testCases.save(new TestCase(suite, "sql",
				new RequestSpec(HttpMethod.GET, "/health", null, null)));
		flushAndClear();
		entityManager.createNativeQuery("""
				UPDATE sentinel.test_case SET assertions = '[
				  {"type": "statusCode", "anyOf": ["2xx"]},
				  {"type": "jsonPath", "path": "$.status", "operator": "EQUALS", "expected": "UP"}
				]' WHERE id = :id
				""").setParameter("id", saved.getId()).executeUpdate();
		flushAndClear();

		assertThat(testCases.findById(saved.getId()).orElseThrow().getAssertions()).containsExactly(
				new StatusCodeAssertion(List.of("2xx")), new JsonPathAssertion("$.status", Operator.EQUALS, "UP"));
	}

	/**
	 * DB에서 읽은 JSON 목록은 가변 목록으로 역직렬화됨. 직접 작성한 방어적 getter가 Lombok @Getter로 바뀌면 내부 목록이 노출되어 실패
	 */
	@Test
	void exposesJsonListsReadOnlyAfterLoading() {
		Project project = projects.save(new Project("billing", null));
		Environment environment = environments.save(new Environment(project, "dev", "https://gw"));
		TestSuite suite = suites.save(new TestSuite(project, "charge", null));
		StatusCodeAssertion assertion = new StatusCodeAssertion(List.of("200"));
		TestCase testCase = testCases.save(new TestCase(suite, "health",
				new RequestSpec(HttpMethod.GET, "/health", null, null), List.of(assertion)));
		TestRun run = runs.save(TestRun.start(suite, environment, TestRun.Trigger.MANUAL, Instant.now()));
		ResolvedRequest request = new ResolvedRequest(HttpMethod.GET, URI.create("https://gw/health"), Map.of(), null);
		TestResult result = results.save(TestResult.responded(run, testCase,
				new HttpExchange(request, 200, Map.of(), "", Duration.ofMillis(1)),
				List.of(AssertionResult.pass(assertion, "was 200"))));
		flushAndClear();

		TestCase foundCase = testCases.findById(testCase.getId()).orElseThrow();
		TestResult foundResult = results.findById(result.getId()).orElseThrow();

		assertThatThrownBy(() -> foundCase.getAssertions().add(new BodyContainsAssertion("ok")))
				.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> foundResult.getAssertionResults().clear())
				.isInstanceOf(UnsupportedOperationException.class);
		assertThat(foundResult.getAssertionResults()).hasSize(1);
	}

	@Test
	void allowsReusingNameOfArchivedRowButNotOfActiveRow() {
		Project project = projects.save(new Project("billing", null));
		TestSuite archived = suites.saveAndFlush(new TestSuite(project, "smoke", null));
		archived.archive(Instant.parse("2026-09-19T00:00:00Z"));
		suites.saveAndFlush(archived);

		TestSuite reused = suites.saveAndFlush(new TestSuite(project, "smoke", "재사용"));

		assertThat(reused.getId()).isNotEqualTo(archived.getId());
		assertThat(suites.findByProjectIdAndArchivedAtIsNullOrderByName(project.getId()))
			.extracting(TestSuite::getId)
			.containsExactly(reused.getId());
		assertThatThrownBy(() -> suites.saveAndFlush(new TestSuite(project, "smoke", "중복")))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void rejectsDuplicateProjectName() {
		projects.saveAndFlush(new Project("billing", null));

		assertThatThrownBy(() -> projects.saveAndFlush(new Project("billing", null)))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void rejectsDuplicateTestCaseNameInSameSuite() {
		TestSuite suite = suites.save(new TestSuite(projects.save(new Project("billing", null)), "charge", null));
		RequestSpec request = new RequestSpec(HttpMethod.GET, "/billing/v1/history", null, null);
		testCases.saveAndFlush(new TestCase(suite, "이력 조회", request));

		assertThatThrownBy(() -> testCases.saveAndFlush(new TestCase(suite, "이력 조회", request)))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	private void flushAndClear() {
		entityManager.flush();
		entityManager.clear();
	}

}
