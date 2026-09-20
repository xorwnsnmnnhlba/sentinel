package com.sentinel.api;

import java.util.Map;

import com.sentinel.TestAuthConfiguration;
import com.sentinel.TestcontainersConfiguration;
import com.sentinel.application.FakeRequestExecutor;
import com.sentinel.application.FakeRequestExecutorConfiguration;
import com.sentinel.application.TestFixtures;
import com.sentinel.domain.Environment;
import com.sentinel.domain.Project;
import com.sentinel.domain.TestSuite;
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
 * projects·environments·suites·cases 등록(POST)·부분 수정(PATCH), schedules cron 수정.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, FakeRequestExecutorConfiguration.class, TestFixtures.class,
		TestAuthConfiguration.class })
class CatalogWriteTest {

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
	void createsAndPatchesProject() {
		MvcTestResult created = post("/api/projects", """
				{"name": "  billing  ", "description": "결제"}""");

		assertThat(created).hasStatus(HttpStatus.CREATED);
		assertThat(created).bodyJson().extractingPath("$.name").isEqualTo("billing");
		String location = created.getResponse().getHeader("Location");
		assertThat(location).matches("/api/projects/\\d+");

		assertThat(patch(location, """
				{"name": "payments"}""")).hasStatusOk().bodyJson().extractingPath("$.description").isEqualTo("결제");
		// 빈 문자열은 설명 제거, 생략한 이름은 유지
		var cleared = assertThat(patch(location, """
				{"description": ""}""")).hasStatusOk().bodyJson();
		cleared.extractingPath("$.name").isEqualTo("payments");
		cleared.extractingPath("$.description").isNull();
		assertThat(mvc.get().uri(location)).bodyJson().extractingPath("$.name").isEqualTo("payments");
	}

	@Test
	void rejectsDuplicateNamesAmongActiveItemsOnly() {
		String first = post("/api/projects", """
				{"name": "billing"}""").getResponse().getHeader("Location");
		String second = post("/api/projects", """
				{"name": "search"}""").getResponse().getHeader("Location");

		assertThat(post("/api/projects", """
				{"name": " billing "}""")).hasStatus(HttpStatus.CONFLICT)
			.bodyJson()
			.extractingPath("$.detail")
			.asString()
			.contains("already in use");
		assertThat(patch(second, """
				{"name": "billing"}""")).hasStatus(HttpStatus.CONFLICT);
		// 자기 이름으로 수정은 허용
		assertThat(patch(first, """
				{"name": "billing"}""")).hasStatusOk();

		// 보관된 이름은 재사용 가능
		assertThat(mvc.delete().uri(first)).hasStatus(HttpStatus.NO_CONTENT);
		assertThat(post("/api/projects", """
				{"name": "billing"}""")).hasStatus(HttpStatus.CREATED);
	}

	@Test
	void allowsSameChildNameUnderDifferentParents() {
		Project project = fixtures.project();
		Project other = fixtures.project();

		assertThat(post("/api/suites", suiteJson(project, "smoke"))).hasStatus(HttpStatus.CREATED);
		assertThat(post("/api/suites", suiteJson(other, "smoke"))).hasStatus(HttpStatus.CREATED);
		assertThat(post("/api/suites", suiteJson(project, "smoke"))).hasStatus(HttpStatus.CONFLICT);
	}

	@Test
	void createsAndPatchesEnvironmentReplacingVariables() {
		Project project = fixtures.project();

		MvcTestResult created = post("/api/environments", """
				{"projectId": %d, "name": "dev", "baseUrl": "https://gw.dev.internal/", "variables": {"userId": "42"}}"""
			.formatted(project.getId()));

		assertThat(created).hasStatus(HttpStatus.CREATED);
		var body = assertThat(created).bodyJson();
		body.extractingPath("$.projectId").isEqualTo(project.getId().intValue());
		body.extractingPath("$.baseUrl").isEqualTo("https://gw.dev.internal");
		body.extractingPath("$.variables.userId").isEqualTo("42");
		String location = created.getResponse().getHeader("Location");

		var patched = assertThat(patch(location, """
				{"baseUrl": "https://gw.stg.internal", "variables": {"orderId": "7"}}""")).hasStatusOk().bodyJson();
		patched.extractingPath("$.name").isEqualTo("dev");
		patched.extractingPath("$.baseUrl").isEqualTo("https://gw.stg.internal");
		patched.extractingPath("$.variables").isEqualTo(Map.of("orderId", "7"));

		assertThat(patch(location, """
				{"variables": {"orderId": null}}""")).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(patch(location, """
				{"baseUrl": "ftp://gw"}""")).hasStatus(HttpStatus.BAD_REQUEST)
			.bodyJson()
			.extractingPath("$.detail")
			.asString()
			.contains("http or https");
		assertThat(mvc.get().uri(location)).bodyJson().extractingPath("$.variables.orderId").isEqualTo("7");
	}

	@Test
	void createsAndPatchesSuiteKeepingCaseCount() {
		Project project = fixtures.project();

		MvcTestResult created = post("/api/suites", suiteJson(project, "smoke"));

		assertThat(created).hasStatus(HttpStatus.CREATED);
		assertThat(created).bodyJson().extractingPath("$.caseCount").isEqualTo(0);
		String location = created.getResponse().getHeader("Location");
		long suiteId = Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
		post("/api/cases", caseJson(suiteId, "health", """
				{"method": "GET", "path": "/health"}"""));

		var patched = assertThat(patch(location, """
				{"name": "regression", "description": "야간 회귀"}""")).hasStatusOk().bodyJson();
		patched.extractingPath("$.name").isEqualTo("regression");
		patched.extractingPath("$.description").isEqualTo("야간 회귀");
		patched.extractingPath("$.caseCount").isEqualTo(1);
	}

	@Test
	void createsCaseThatRunsWithItsRequestAndAssertions() {
		Project project = fixtures.project();
		Environment environment = fixtures.environment(project, Map.of("userId", "42"));
		TestSuite suite = fixtures.suite(project);

		MvcTestResult created = post("/api/cases", """
				{"suiteId": %d, "name": "사용자 조회",
				 "request": {"method": "POST", "path": "/users/{{userId}}",
				             "headers": {"Accept": ["application/json"]}, "body": "{}"},
				 "assertions": [{"type": "statusCode", "anyOf": ["201"]}]}""".formatted(suite.getId()));

		assertThat(created).hasStatus(HttpStatus.CREATED);
		var body = assertThat(created).bodyJson();
		body.extractingPath("$.suiteId").isEqualTo(suite.getId().intValue());
		body.extractingPath("$.request.method").isEqualTo("POST");
		body.extractingPath("$.request.headers.Accept[0]").isEqualTo("application/json");
		body.extractingPath("$.assertions[0].type").isEqualTo("statusCode");
		String location = created.getResponse().getHeader("Location");
		long caseId = Long.parseLong(location.substring(location.lastIndexOf('/') + 1));

		executor.respond(201, Map.of(), "");
		MvcTestResult run = post("/api/runs", """
				{"caseId": %d, "environmentId": %d}""".formatted(caseId, environment.getId()));
		assertThat(run).hasStatus(HttpStatus.CREATED).bodyJson().extractingPath("$.results[0].verdict").isEqualTo("PASSED");
		assertThat(executor.requests()).singleElement()
			.satisfies(request -> assertThat(request.uri().toString()).isEqualTo("https://gw.dev.internal/users/42"));
	}

	@Test
	void patchesCaseReplacingRequestAndResettingAssertions() {
		TestSuite suite = fixtures.suite(fixtures.project());
		String location = post("/api/cases", caseJson(suite.getId(), "health", """
				{"method": "GET", "path": "/health"}""", """
				[{"type": "responseTime", "maxMillis": 500}]""")).getResponse().getHeader("Location");

		var patched = assertThat(patch(location, """
				{"request": {"method": "DELETE", "path": "/cache"}, "assertions": []}""")).hasStatusOk().bodyJson();
		patched.extractingPath("$.name").isEqualTo("health");
		patched.extractingPath("$.request.method").isEqualTo("DELETE");
		patched.extractingPath("$.request.path").isEqualTo("/cache");
		patched.extractingPath("$.request.headers").isEqualTo(Map.of());
		patched.extractingPath("$.assertions").asArray().isEmpty();

		// 생략한 판정 규칙은 유지
		assertThat(patch(location, """
				{"assertions": [{"type": "bodyContains", "text": "ok"}]}""")).hasStatusOk();
		assertThat(patch(location, """
				{"name": "cache"}""")).hasStatusOk().bodyJson().extractingPath("$.assertions[0].text").isEqualTo("ok");
	}

	@Test
	void rejectsInvalidCaseDefinitions() {
		TestSuite suite = fixtures.suite(fixtures.project());

		// 경로는 /로 시작
		assertThat(post("/api/cases", caseJson(suite.getId(), "a", """
				{"method": "GET", "path": "health"}"""))).hasStatus(HttpStatus.BAD_REQUEST)
			.bodyJson()
			.extractingPath("$.detail")
			.asString()
			.contains("path must start with '/'");
		// 지원하지 않는 메서드, 판정 규칙 종류, 잘못된 규칙 값은 본문 해석 단계에서 거부
		assertThat(post("/api/cases", caseJson(suite.getId(), "b", """
				{"method": "FETCH", "path": "/a"}"""))).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(post("/api/cases", caseJson(suite.getId(), "c", """
				{"method": "GET", "path": "/a"}""", """
				[{"type": "unknown"}]"""))).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(post("/api/cases", caseJson(suite.getId(), "d", """
				{"method": "GET", "path": "/a"}""", """
				[{"type": "statusCode", "anyOf": ["abc"]}]"""))).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(post("/api/cases", """
				{"suiteId": %d, "name": "e"}""".formatted(suite.getId()))).hasStatus(HttpStatus.BAD_REQUEST);

		assertThat(mvc.get().uri("/api/cases?suiteId={id}", suite.getId())).bodyJson()
			.extractingPath("$.length()")
			.isEqualTo(0);
	}

	@Test
	void rejectsMissingFieldsAndUnknownOrArchivedParents() {
		Project project = fixtures.project();

		assertThat(post("/api/projects", "{}")).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(post("/api/projects", """
				{"name": "%s"}""".formatted("x".repeat(101)))).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(patch("/api/projects/" + project.getId(), """
				{"name": "  "}""")).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(post("/api/environments", """
				{"projectId": %d, "name": "dev"}""".formatted(project.getId()))).hasStatus(HttpStatus.BAD_REQUEST);

		assertThat(post("/api/suites", """
				{"projectId": -1, "name": "smoke"}""")).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(post("/api/cases", caseJson(-1, "a", """
				{"method": "GET", "path": "/a"}"""))).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(patch("/api/environments/-1", """
				{"name": "dev"}""")).hasStatus(HttpStatus.NOT_FOUND);

		mvc.delete().uri("/api/projects/{id}", project.getId()).exchange();
		assertThat(post("/api/suites", suiteJson(project, "smoke"))).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(patch("/api/projects/" + project.getId(), """
				{"name": "revived"}""")).hasStatus(HttpStatus.NOT_FOUND);
	}

	@Test
	void patchesScheduleCron() {
		Project project = fixtures.project();
		Environment environment = fixtures.environment(project, Map.of());
		TestSuite suite = fixtures.suite(project);
		String location = post("/api/schedules", """
				{"suiteId": %d, "environmentId": %d, "cron": "10 9 * * 1-5"}"""
			.formatted(suite.getId(), environment.getId())).getResponse().getHeader("Location");

		var changed = assertThat(patch(location, """
				{"cron": "30  17 * * 1-5"}""")).hasStatusOk().bodyJson();
		changed.extractingPath("$.cron").isEqualTo("30 17 * * 1-5");
		changed.extractingPath("$.nextRunAt").asString().endsWith("08:30:00Z");

		assertThat(patch(location, """
				{"cron": "0 0 30 2 *"}""")).hasStatus(HttpStatus.BAD_REQUEST);
		// 비활성화와 cron 변경을 함께 지정하면 cron만 바뀌고 다음 실행 시각 없음
		var disabled = assertThat(patch(location, """
				{"enabled": false, "cron": "0 12 * * *"}""")).hasStatusOk().bodyJson();
		disabled.extractingPath("$.cron").isEqualTo("0 12 * * *");
		disabled.extractingPath("$.nextRunAt").isNull();
	}

	private MvcTestResult post(String uri, String body) {
		return mvc.post().uri(uri).contentType(MediaType.APPLICATION_JSON).content(body).exchange();
	}

	private MvcTestResult patch(String uri, String body) {
		return mvc.patch().uri(uri).contentType(MediaType.APPLICATION_JSON).content(body).exchange();
	}

	private static String suiteJson(Project project, String name) {
		return """
				{"projectId": %d, "name": "%s"}""".formatted(project.getId(), name);
	}

	private static String caseJson(long suiteId, String name, String request) {
		return """
				{"suiteId": %d, "name": "%s", "request": %s}""".formatted(suiteId, name, request);
	}

	private static String caseJson(long suiteId, String name, String request, String assertions) {
		return """
				{"suiteId": %d, "name": "%s", "request": %s, "assertions": %s}""".formatted(suiteId, name, request,
				assertions);
	}

}
