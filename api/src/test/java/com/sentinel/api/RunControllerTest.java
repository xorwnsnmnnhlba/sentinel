package com.sentinel.api;

import java.util.Map;

import com.jayway.jsonpath.JsonPath;
import com.sentinel.TestAuthConfiguration;
import com.sentinel.TestcontainersConfiguration;
import com.sentinel.application.FakeRequestExecutor;
import com.sentinel.application.FakeRequestExecutorConfiguration;
import com.sentinel.application.TestFixtures;
import com.sentinel.domain.Environment;
import com.sentinel.domain.Project;
import com.sentinel.domain.TestCase;
import com.sentinel.domain.TestSuite;
import com.sentinel.runner.HttpMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, FakeRequestExecutorConfiguration.class, TestFixtures.class,
		TestAuthConfiguration.class })
class RunControllerTest {

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
	void createsSuiteRunAndServesItAtLocation() throws Exception {
		Project project = fixtures.project();
		Environment environment = fixtures.environment(project, Map.of());
		TestSuite suite = fixtures.suite(project);
		TestCase testCase = fixtures.testCase(suite, HttpMethod.GET, "/health", null, null);
		executor.respond(200, Map.of(), "{\"status\":\"UP\"}");

		MvcTestResult created = createRun("{\"suiteId\": %d, \"environmentId\": %d}".formatted(suite.getId(),
				environment.getId()));

		assertThat(created).hasStatus(HttpStatus.CREATED);
		assertThat(created).bodyJson().extractingPath("$.trigger").isEqualTo("MANUAL");
		assertThat(created).bodyJson().extractingPath("$.summary.passed").isEqualTo(1);
		String location = created.getResponse().getHeader("Location");
		long runId = ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();
		assertThat(location).isEqualTo("/api/runs/" + runId);

		var detail = assertThat(mvc.get().uri(location)).hasStatusOk().bodyJson();
		detail.extractingPath("$.suiteName").isEqualTo(suite.getName());
		detail.extractingPath("$.environmentName").isEqualTo("dev");
		detail.extractingPath("$.results[0].testCaseName").isEqualTo(testCase.getName());
		detail.extractingPath("$.results[0].response.body").isEqualTo("{\"status\":\"UP\"}");
	}

	@Test
	void createsSingleCaseRun() {
		Project project = fixtures.project();
		Environment environment = fixtures.environment(project, Map.of());
		TestSuite suite = fixtures.suite(project);
		TestCase target = fixtures.testCase(suite, HttpMethod.GET, "/a", null, null);
		fixtures.testCase(suite, HttpMethod.GET, "/b", null, null);

		MvcTestResult created = createRun("{\"caseId\": %d, \"environmentId\": %d}".formatted(target.getId(),
				environment.getId()));

		assertThat(created).hasStatus(HttpStatus.CREATED);
		assertThat(created).bodyJson().extractingPath("$.summary.total").isEqualTo(1);
		assertThat(executor.requests()).singleElement().satisfies(request -> assertThat(request.uri().getPath()).isEqualTo("/a"));
	}

	@Test
	void createsARunThroughTheChosenCaseAndListsIt() {
		Project project = fixtures.project();
		Environment environment = fixtures.environment(project, Map.of());
		TestSuite suite = fixtures.suite(project);
		fixtures.testCase(suite, HttpMethod.GET, "/a", null, null);
		TestCase through = fixtures.testCase(suite, HttpMethod.GET, "/b", null, null);
		fixtures.testCase(suite, HttpMethod.GET, "/c", null, null);

		MvcTestResult created = createRun("{\"throughCaseId\": %d, \"environmentId\": %d}"
			.formatted(through.getId(), environment.getId()));

		assertThat(created).hasStatus(HttpStatus.CREATED);
		assertThat(created).bodyJson().extractingPath("$.summary.total").isEqualTo(2);
		assertThat(created).bodyJson().extractingPath("$.throughCase.name").isEqualTo(through.getName());
		assertThat(created).bodyJson().extractingPath("$.testCase").isNull();
		assertThat(executor.requests()).extracting(request -> request.uri().getPath()).containsExactly("/a", "/b");
		var listed = assertThat(mvc.get().uri("/api/runs")).bodyJson();
		listed.extractingPath("$.content[0].throughCase.id").isEqualTo(through.getId().intValue());
		listed.extractingPath("$.content[0].testCase").isNull();
	}

	@Test
	void requiresExactlyOneTarget() {
		assertThat(createRun("{\"environmentId\": 1}")).hasStatus(HttpStatus.BAD_REQUEST);
		// package-private record의 @AssertTrue 검증 적용 여부. 미적용 시 스위트 실행으로 넘어가 404
		assertThat(createRun("{\"suiteId\": 1, \"caseId\": 1, \"environmentId\": 1}")).hasStatus(HttpStatus.BAD_REQUEST)
			.bodyJson()
			.extractingPath("$.detail")
			.asString()
			.contains("Invalid request content");
		assertThat(createRun("{\"suiteId\": 1}")).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(createRun("{\"caseId\": 1, \"throughCaseId\": 1, \"environmentId\": 1}"))
			.hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(createRun("{\"suiteId\": 1, \"throughCaseId\": 1, \"environmentId\": 1}"))
			.hasStatus(HttpStatus.BAD_REQUEST);
	}

	@Test
	void returnsProblemDetailForMissingTarget() {
		assertThat(createRun("{\"caseId\": -1, \"environmentId\": 1}")).hasStatus(HttpStatus.NOT_FOUND)
			.bodyJson()
			.extractingPath("$.detail")
			.isEqualTo("test case -1 not found");
		assertThat(createRun("{\"throughCaseId\": -1, \"environmentId\": 1}")).hasStatus(HttpStatus.NOT_FOUND)
			.bodyJson()
			.extractingPath("$.detail")
			.isEqualTo("test case -1 not found");
		assertThat(mvc.get().uri("/api/runs/{id}", -1)).hasStatus(HttpStatus.NOT_FOUND);
	}

	@Test
	void listsRunsNewestFirstWithFilterAndPaging() {
		Project project = fixtures.project();
		Environment environment = fixtures.environment(project, Map.of());
		TestSuite suite = fixtures.suite(project);
		fixtures.testCase(suite, HttpMethod.GET, "/health", null, null);
		TestSuite other = fixtures.suite(project);
		for (int i = 0; i < 2; i++) {
			createRun("{\"suiteId\": %d, \"environmentId\": %d}".formatted(suite.getId(), environment.getId()));
		}
		createRun("{\"suiteId\": %d, \"environmentId\": %d}".formatted(other.getId(), environment.getId()));

		var filtered = assertThat(mvc.get().uri("/api/runs?suiteId={id}&size=1", suite.getId())).hasStatusOk().bodyJson();
		filtered.extractingPath("$.totalElements").isEqualTo(2);
		filtered.extractingPath("$.totalPages").isEqualTo(2);
		filtered.extractingPath("$.content[0].suite.name").isEqualTo(suite.getName());
		filtered.extractingPath("$.content[0].summary.passed").isEqualTo(1);

		assertThat(mvc.get().uri("/api/runs")).bodyJson().extractingPath("$.totalElements").isEqualTo(3);
		assertThat(mvc.get().uri("/api/runs?suiteId=-1")).bodyJson().extractingPath("$.totalElements").isEqualTo(0);
	}

	private MvcTestResult createRun(String body) {
		return mvc.post().uri("/api/runs").contentType(MediaType.APPLICATION_JSON).content(body).exchange();
	}

}
