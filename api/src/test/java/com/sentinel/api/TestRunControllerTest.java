package com.sentinel.api;

import java.util.Map;

import com.sentinel.TestcontainersConfiguration;
import com.sentinel.application.FakeRequestExecutor;
import com.sentinel.application.FakeRequestExecutorConfiguration;
import com.sentinel.application.TestFixtures;
import com.sentinel.domain.Environment;
import com.sentinel.domain.Project;
import com.sentinel.domain.TestCase;
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
@Import({ TestcontainersConfiguration.class, FakeRequestExecutorConfiguration.class, TestFixtures.class })
class TestRunControllerTest {

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
	void runsTestCaseAndReturnsCreatedRun() {
		Project project = fixtures.project();
		Environment environment = fixtures.environment(project, Map.of());
		TestCase testCase = fixtures.testCase(project, HttpMethod.GET, "/health", null, null);
		executor.respond(200, Map.of(), "{\"status\":\"UP\"}");

		MvcTestResult result = mvc.post()
			.uri("/api/test-cases/{id}/runs", testCase.getId())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"environmentId\": " + environment.getId() + "}")
			.exchange();

		assertThat(result).hasStatus(HttpStatus.CREATED);
		assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("COMPLETED");
		assertThat(result).bodyJson().extractingPath("$.results[0].response.statusCode").isEqualTo(200);
		assertThat(result).bodyJson().extractingPath("$.results[0].response.body").isEqualTo("{\"status\":\"UP\"}");

		String location = result.getResponse().getHeader("Location");
		assertThat(location).startsWith("/api/test-runs/");
		assertThat(mvc.get().uri(location)).hasStatusOk().bodyJson().extractingPath("$.status").isEqualTo("COMPLETED");
	}

	@Test
	void returnsProblemDetailForMissingTestCase() {
		assertThat(mvc.post()
			.uri("/api/test-cases/{id}/runs", -1)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"environmentId\": 1}"))
			.hasStatus(HttpStatus.NOT_FOUND)
			.bodyJson()
			.extractingPath("$.detail")
			.isEqualTo("test case -1 not found");
	}

	@Test
	void rejectsMissingEnvironmentId() {
		assertThat(mvc.post().uri("/api/test-cases/{id}/runs", 1).contentType(MediaType.APPLICATION_JSON).content("{}"))
			.hasStatus(HttpStatus.BAD_REQUEST);
	}

}
