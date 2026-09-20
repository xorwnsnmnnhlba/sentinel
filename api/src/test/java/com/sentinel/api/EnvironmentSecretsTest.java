package com.sentinel.api;

import java.util.List;
import java.util.Map;

import com.sentinel.TestAuthConfiguration;
import com.sentinel.TestcontainersConfiguration;
import com.sentinel.application.FakeRequestExecutor;
import com.sentinel.application.FakeRequestExecutorConfiguration;
import com.sentinel.application.TestFixtures;
import com.sentinel.application.TestRunService;
import com.sentinel.domain.Environment;
import com.sentinel.domain.Project;
import com.sentinel.domain.TestRun;
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

/**
 * 시크릿 등록·사용. 값은 응답·실행 이력 어디에도 남지 않고, 실제 전송에는 원래 값 사용
 */
@SpringBootTest(properties = "sentinel.secret.key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, FakeRequestExecutorConfiguration.class, TestFixtures.class,
		TestAuthConfiguration.class })
class EnvironmentSecretsTest {

	private static final String TOKEN = "super-secret-token-value";

	@Autowired
	private MockMvcTester mvc;

	@Autowired
	private TestRunService testRunService;

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
	void keepsSecretValuesOutOfResponses() throws Exception {
		Project project = fixtures.project();

		MvcTestResult created = post("""
				{"projectId": %d, "name": "dev", "baseUrl": "https://gw", "variables": {"userId": "42"},
				 "secrets": {"token": "%s"}}""".formatted(project.getId(), TOKEN));

		assertThat(created).hasStatus(HttpStatus.CREATED);
		assertThat(created).bodyJson().extractingPath("$.secretNames").asArray().containsExactly("token");
		assertThat(created.getResponse().getContentAsString()).doesNotContain(TOKEN);

		String location = created.getResponse().getHeader("Location");
		var found = assertThat(mvc.get().uri(location)).hasStatusOk();
		found.bodyJson().extractingPath("$.variables.userId").isEqualTo("42");
		found.bodyJson().extractingPath("$.secretNames").asArray().containsExactly("token");
		assertThat(mvc.get().uri("/api/environments").exchange().getResponse().getContentAsString())
			.doesNotContain(TOKEN);
	}

	@Test
	void addsReplacesAndRemovesSecretsByName() {
		Project project = fixtures.project();
		String location = post("""
				{"projectId": %d, "name": "dev", "baseUrl": "https://gw", "secrets": {"token": "%s"}}"""
			.formatted(project.getId(), TOKEN)).getResponse().getHeader("Location");

		// 목록에 없는 이름은 유지, 값이 있으면 교체, null이면 삭제
		assertThat(patch(location, """
				{"secrets": {"apiKey": "key-value"}}""")).hasStatusOk()
			.bodyJson()
			.extractingPath("$.secretNames")
			.asArray()
			.containsExactlyInAnyOrder("token", "apiKey");
		assertThat(patch(location, """
				{"secrets": {"token": null}}""")).hasStatusOk()
			.bodyJson()
			.extractingPath("$.secretNames")
			.asArray()
			.containsExactly("apiKey");
	}

	@Test
	void rejectsNamesUsedByVariables() {
		Project project = fixtures.project();

		assertThat(post("""
				{"projectId": %d, "name": "dev", "baseUrl": "https://gw", "variables": {"token": "plain"},
				 "secrets": {"token": "%s"}}""".formatted(project.getId(), TOKEN)))
			.hasStatus(HttpStatus.BAD_REQUEST)
			.bodyJson()
			.extractingPath("$.detail")
			.asString()
			.contains("already used as a variable");
	}

	@Test
	void sendsTheRealValueButStoresItMasked() throws Exception {
		Project project = fixtures.project();
		Environment environment = fixtures.environment(project, Map.of("userId", "42"));
		mvc.patch()
			.uri("/api/environments/{id}", environment.getId())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"secrets\": {\"token\": \"%s\"}}".formatted(TOKEN))
			.exchange();
		TestSuite suite = fixtures.suite(project);
		fixtures.testCase(suite, HttpMethod.POST, "/users/{{userId}}?key={{token}}",
				Map.of("Authorization", List.of("Bearer {{token}}"), "X-Token", List.of("{{token}}")),
				"{\"token\": \"{{token}}\"}");
		executor.respond(200, Map.of("X-Echo", List.of(TOKEN)), "echo " + TOKEN);

		long runId = testRunService.runSuite(suite.getId(), environment.getId(), TestRun.Trigger.MANUAL);

		// 실제 전송에는 원래 값 사용
		assertThat(executor.requests()).singleElement().satisfies(request -> {
			assertThat(request.uri().toString()).contains(TOKEN);
			assertThat(request.headers().get("X-Token")).containsExactly(TOKEN);
			assertThat(request.body()).contains(TOKEN);
		});
		// 이력에는 남지 않음
		String stored = mvc.get().uri("/api/runs/{id}", runId).exchange().getResponse().getContentAsString();
		assertThat(stored).doesNotContain(TOKEN).contains("****");
	}

	private MvcTestResult post(String body) {
		return mvc.post().uri("/api/environments").contentType(MediaType.APPLICATION_JSON).content(body).exchange();
	}

	private MvcTestResult patch(String uri, String body) {
		return mvc.patch().uri(uri).contentType(MediaType.APPLICATION_JSON).content(body).exchange();
	}

}
