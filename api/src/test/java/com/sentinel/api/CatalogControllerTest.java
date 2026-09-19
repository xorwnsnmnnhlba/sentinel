package com.sentinel.api;

import java.util.List;
import java.util.Map;

import com.sentinel.TestcontainersConfiguration;
import com.sentinel.application.FakeRequestExecutor;
import com.sentinel.application.FakeRequestExecutorConfiguration;
import com.sentinel.application.TestFixtures;
import com.sentinel.domain.Environment;
import com.sentinel.domain.Project;
import com.sentinel.domain.TestCase;
import com.sentinel.domain.TestSuite;
import com.sentinel.runner.HttpMethod;
import com.sentinel.runner.assertion.JsonPathAssertion;
import com.sentinel.runner.assertion.Operator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * projects·environments·suites·cases 조회. 목록의 부모 ID는 필터이므로 미존재 시 빈 목록, 단건만 404.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, FakeRequestExecutorConfiguration.class, TestFixtures.class })
class CatalogControllerTest {

	@Autowired
	private MockMvcTester mvc;

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
	void listsAndGetsProjectsAndEnvironments() {
		Project project = fixtures.project();
		Environment environment = fixtures.environment(project, Map.of("userId", "42"));
		fixtures.environment(fixtures.project(), Map.of());

		assertThat(mvc.get().uri("/api/projects")).hasStatusOk().bodyJson().extractingPath("$.length()").isEqualTo(2);
		assertThat(mvc.get().uri("/api/projects/{id}", project.getId())).hasStatusOk()
			.bodyJson()
			.extractingPath("$.name")
			.isEqualTo(project.getName());

		var environments = assertThat(mvc.get().uri("/api/environments?projectId={id}", project.getId())).hasStatusOk()
			.bodyJson();
		environments.extractingPath("$.length()").isEqualTo(1);
		environments.extractingPath("$[0].projectId").isEqualTo(project.getId().intValue());
		environments.extractingPath("$[0].variables.userId").isEqualTo("42");
		assertThat(mvc.get().uri("/api/environments")).bodyJson().extractingPath("$.length()").isEqualTo(2);
		assertThat(mvc.get().uri("/api/environments/{id}", environment.getId())).hasStatusOk()
			.bodyJson()
			.extractingPath("$.baseUrl")
			.isEqualTo("https://gw.dev.internal");
	}

	@Test
	void listsAndGetsSuitesWithCaseCounts() {
		Project project = fixtures.project();
		TestSuite withCases = fixtures.suite(project);
		fixtures.testCase(withCases, HttpMethod.GET, "/a", null, null);
		fixtures.testCase(withCases, HttpMethod.GET, "/b", null, null);
		TestSuite empty = fixtures.suite(project);
		fixtures.suite(fixtures.project());

		var suites = assertThat(mvc.get().uri("/api/suites?projectId={id}", project.getId())).hasStatusOk().bodyJson();
		suites.extractingPath("$.length()").isEqualTo(2);
		suites.extractingPath("$[?(@.id == %d)].caseCount".formatted(withCases.getId())).asArray().containsExactly(2);
		suites.extractingPath("$[?(@.id == %d)].caseCount".formatted(empty.getId())).asArray().containsExactly(0);
		assertThat(mvc.get().uri("/api/suites")).bodyJson().extractingPath("$.length()").isEqualTo(3);
		assertThat(mvc.get().uri("/api/suites/{id}", withCases.getId())).hasStatusOk()
			.bodyJson()
			.extractingPath("$.caseCount")
			.isEqualTo(2);
	}

	@Test
	void listsAndGetsCasesWithRequestAndTypedAssertions() {
		TestSuite suite = fixtures.suite(fixtures.project());
		TestCase testCase = fixtures.testCase(suite, HttpMethod.POST, "/users/{{id}}",
				Map.of("Accept", List.of("application/json")), "{}",
				List.of(new JsonPathAssertion("$.status", Operator.EQUALS, "UP")));

		var cases = assertThat(mvc.get().uri("/api/cases?suiteId={id}", suite.getId())).hasStatusOk().bodyJson();
		cases.extractingPath("$[0].suiteId").isEqualTo(suite.getId().intValue());
		cases.extractingPath("$[0].request.method").isEqualTo("POST");
		cases.extractingPath("$[0].request.path").isEqualTo("/users/{{id}}");
		cases.extractingPath("$[0].request.headers.Accept[0]").isEqualTo("application/json");
		cases.extractingPath("$[0].assertions[0].type").isEqualTo("jsonPath");
		cases.extractingPath("$[0].assertions[0].expected").isEqualTo("UP");
		assertThat(mvc.get().uri("/api/cases/{id}", testCase.getId())).hasStatusOk()
			.bodyJson()
			.extractingPath("$.name")
			.isEqualTo(testCase.getName());
	}

	@Test
	void treatsUnknownParentFilterAsEmptyAndMissingItemAsNotFound() {
		assertThat(mvc.get().uri("/api/environments?projectId=-1")).hasStatusOk().bodyJson().extractingPath("$.length()").isEqualTo(0);
		assertThat(mvc.get().uri("/api/suites?projectId=-1")).hasStatusOk().bodyJson().extractingPath("$.length()").isEqualTo(0);
		assertThat(mvc.get().uri("/api/cases?suiteId=-1")).hasStatusOk().bodyJson().extractingPath("$.length()").isEqualTo(0);

		assertThat(mvc.get().uri("/api/projects/{id}", -1)).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(mvc.get().uri("/api/environments/{id}", -1)).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(mvc.get().uri("/api/suites/{id}", -1)).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(mvc.get().uri("/api/cases/{id}", -1)).hasStatus(HttpStatus.NOT_FOUND);
	}

}
