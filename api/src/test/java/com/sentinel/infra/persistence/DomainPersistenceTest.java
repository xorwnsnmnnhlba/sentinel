package com.sentinel.infra.persistence;

import java.util.List;
import java.util.Map;

import com.sentinel.TestcontainersConfiguration;
import com.sentinel.domain.Environment;
import com.sentinel.domain.Project;
import com.sentinel.domain.RequestSpec;
import com.sentinel.domain.TestCase;
import com.sentinel.domain.TestSuite;
import com.sentinel.runner.HttpMethod;
import com.sentinel.runner.assertion.Assertion;
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

		assertThat(versions).containsExactly("1");
	}

	@Test
	void mapsJsonColumnsToJsonb() {
		List<Object> types = entityManager.createNativeQuery("""
				SELECT data_type FROM information_schema.columns
				WHERE table_schema = 'sentinel'
				  AND (table_name, column_name) IN (('environment', 'variables'), ('test_case', 'headers'))
				""").getResultList();

		assertThat(types).containsExactly("jsonb", "jsonb");
	}

	@Test
	void storesEnvironmentVariablesAsJson() {
		Project project = projects.save(new Project("billing", "결제 모듈"));
		Environment environment = new Environment(project, "dev", "https://gw.dev.internal");
		environment.putVariable("userId", "42");
		environment.putVariable("locale", "ko-KR");
		environments.save(environment);
		flushAndClear();

		Environment found = environments.findByProjectIdOrderByName(project.getId()).getFirst();

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

		List<TestCase> found = testCases.findBySuiteIdOrderById(suite.getId());

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
