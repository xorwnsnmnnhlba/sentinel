package com.sentinel.api;

import java.util.List;
import java.util.Map;

import com.sentinel.TestAuthConfiguration;
import com.sentinel.TestcontainersConfiguration;
import com.sentinel.application.FakeRequestExecutor;
import com.sentinel.application.FakeRequestExecutorConfiguration;
import com.sentinel.application.InlineBackgroundRunsConfiguration;
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
@Import({ TestcontainersConfiguration.class, FakeRequestExecutorConfiguration.class,
		InlineBackgroundRunsConfiguration.class, TestFixtures.class,
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

		var conflict = assertThat(post("/api/projects", """
				{"name": " billing "}""")).hasStatus(HttpStatus.CONFLICT).bodyJson();
		conflict.extractingPath("$.detail").asString().contains("already in use");
		conflict.extractingPath("$.code").isEqualTo("NAME_IN_USE");
		conflict.extractingPath("$.params").isEqualTo(Map.of("name", "billing"));
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
		body.extractingPath("$.project.id").isEqualTo(project.getId().intValue());
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
		body.extractingPath("$.suite.id").isEqualTo(suite.getId().intValue());
		body.extractingPath("$.request.method").isEqualTo("POST");
		body.extractingPath("$.request.headers.Accept[0]").isEqualTo("application/json");
		body.extractingPath("$.assertions[0].type").isEqualTo("statusCode");
		String location = created.getResponse().getHeader("Location");
		long caseId = Long.parseLong(location.substring(location.lastIndexOf('/') + 1));

		executor.respond(201, Map.of(), "");
		MvcTestResult run = post("/api/runs", """
				{"caseId": %d, "environmentId": %d}""".formatted(caseId, environment.getId()));
		assertThat(run).hasStatus(HttpStatus.ACCEPTED).bodyJson().extractingPath("$.results[0].verdict").isEqualTo("PASSED");
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
	void createsAndPatchesCaseExtractors() {
		TestSuite suite = fixtures.suite(fixtures.project());

		MvcTestResult created = post("/api/cases", """
				{"suiteId": %d, "name": "login", "request": {"method": "POST", "path": "/login"},
				 "extractors": [{"name": "token", "from": "BODY", "expression": "$.data.token", "secret": true},
				                {"name": "requestId", "from": "HEADER", "expression": "X-Request-Id"}]}"""
			.formatted(suite.getId()));

		assertThat(created).hasStatus(HttpStatus.CREATED);
		var body = assertThat(created).bodyJson();
		body.extractingPath("$.extractors[0].name").isEqualTo("token");
		body.extractingPath("$.extractors[0].secret").isEqualTo(true);
		// 생략한 가림 여부는 false
		body.extractingPath("$.extractors[1].secret").isEqualTo(false);
		String location = created.getResponse().getHeader("Location");

		// 생략하면 유지, 지정하면 전체 교체
		assertThat(patch(location, """
				{"name": "sign in"}""")).hasStatusOk().bodyJson().extractingPath("$.extractors.length()").isEqualTo(2);
		assertThat(patch(location, """
				{"extractors": []}""")).hasStatusOk().bodyJson().extractingPath("$.extractors").asArray().isEmpty();
		assertThat(mvc.get().uri("/api/cases?suiteId={id}", suite.getId())).bodyJson()
			.extractingPath("$[0].extractors")
			.asArray()
			.isEmpty();
	}

	@Test
	void namesTheRuleWhoseExpressionCannotBeRead() {
		TestSuite suite = fixtures.suite(fixtures.project());
		String request = """
				{"method": "GET", "path": "/a"}""";

		// 판정 규칙은 JSON을 읽으면서 검증. 몇 번째 규칙인지와 화면이 한국어 문장을 만들 code·params를 함께
		var path = assertThat(post("/api/cases", """
				{"suiteId": %d, "name": "a", "request": %s, "assertions": [
				  {"type": "statusCode", "anyOf": ["200"]},
				  {"type": "jsonPath", "path": "$.[", "operator": "EXISTS"}]}""".formatted(suite.getId(), request)))
			.hasStatus(HttpStatus.BAD_REQUEST)
			.bodyJson();
		path.extractingPath("$.detail").isEqualTo("invalid JSON path: $.[");
		path.extractingPath("$.code").isEqualTo("INVALID_JSON_PATH");
		path.extractingPath("$.params").isEqualTo(Map.of("expression", "$.[", "list", "assertions", "index", "1"));

		assertThat(post("/api/cases", """
				{"suiteId": %d, "name": "b", "request": %s, "assertions": [
				  {"type": "header", "name": "X-Id", "operator": "MATCHES", "value": "("}]}"""
			.formatted(suite.getId(), request))).bodyJson()
			.extractingPath("$.params")
			.isEqualTo(Map.of("expression", "(", "list", "assertions", "index", "0"));
		assertThat(post("/api/cases", extractorCaseJson(suite.getId(), "c", request, """
				[{"name": "id", "from": "BODY", "expression": "$.id"},
				 {"name": "token", "from": "BODY", "expression": "$.["}]"""))).bodyJson()
			.extractingPath("$.params")
			.isEqualTo(Map.of("expression", "$.[", "list", "extractors", "index", "1"));

		// 종류가 없는 오류(JSON 문법 등)는 Spring 기본 문구 그대로, code 없음
		var malformed = assertThat(post("/api/cases", "{\"suiteId\": ")).hasStatus(HttpStatus.BAD_REQUEST).bodyJson();
		malformed.extractingPath("$.detail").isEqualTo("Failed to read request");
		malformed.doesNotHavePath("$.code");
		assertThat(post("/api/cases", extractorCaseJson(suite.getId(), "d", request, """
				[{"name": "user id", "from": "BODY", "expression": "$.id"}]"""))).bodyJson()
			.doesNotHavePath("$.code");
	}

	@Test
	void namesWhatIsWrongWithAnEnvironmentOrAName() {
		Project project = fixtures.project();

		var scheme = assertThat(post("/api/environments", """
				{"projectId": %d, "name": "dev", "baseUrl": "ftp://gw"}""".formatted(project.getId())))
			.hasStatus(HttpStatus.BAD_REQUEST)
			.bodyJson();
		scheme.extractingPath("$.detail").isEqualTo("baseUrl must use http or https: ftp://gw");
		scheme.extractingPath("$.code").isEqualTo("BASE_URL_SCHEME");
		scheme.extractingPath("$.params").isEqualTo(Map.of("value", "ftp://gw"));
		// 이 테스트는 암호화 키 없이 실행. 시크릿은 저장할 수 없음
		assertThat(post("/api/environments", """
				{"projectId": %d, "name": "dev", "baseUrl": "https://gw", "secrets": {"token": "x"}}"""
			.formatted(project.getId()))).bodyJson()
			.extractingPath("$.code")
			.isEqualTo("SECRETS_DISABLED");
		assertThat(post("/api/projects", """
				{"name": "   "}""")).hasStatus(HttpStatus.BAD_REQUEST)
			.bodyJson()
			.extractingPath("$.params")
			.isEqualTo(Map.of("target", "name"));
	}

	@Test
	void saysTheSecretsCannotBeReadWithoutAKey() {
		Project project = fixtures.project();
		Environment environment = fixtures.environmentWithSecret(project, "token", "cipher");
		long caseId = createCase(fixtures.suite(project), "a");

		assertThat(post("/api/runs", """
				{"caseId": %d, "environmentId": %d}""".formatted(caseId, environment.getId())))
			.hasStatus(HttpStatus.BAD_REQUEST)
			.bodyJson()
			.extractingPath("$.code")
			.isEqualTo("SECRETS_KEY_MISSING");
	}

	@Test
	void rejectsInvalidExtractors() {
		TestSuite suite = fixtures.suite(fixtures.project());
		String request = """
				{"method": "GET", "path": "/a"}""";

		assertThat(post("/api/cases", extractorCaseJson(suite.getId(), "a", request, """
				[{"name": "user id", "from": "BODY", "expression": "$.id"}]"""))).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(post("/api/cases", extractorCaseJson(suite.getId(), "b", request, """
				[{"name": "id", "from": "QUERY", "expression": "id"}]"""))).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(post("/api/cases", extractorCaseJson(suite.getId(), "c", request, """
				[{"name": "id", "from": "BODY", "expression": "$.["}]"""))).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(post("/api/cases", extractorCaseJson(suite.getId(), "d", request, """
				[{"name": "id", "from": "BODY", "expression": "$.id"},
				 {"name": "id", "from": "HEADER", "expression": "X-Id"}]"""))).hasStatus(HttpStatus.BAD_REQUEST)
			.bodyJson()
			.extractingPath("$.detail")
			.asString()
			.contains("'id' is duplicated");

		assertThat(mvc.get().uri("/api/cases?suiteId={id}", suite.getId())).bodyJson()
			.extractingPath("$.length()")
			.isEqualTo(0);
	}

	@Test
	void reordersCasesAndRunsThemInThatOrder() {
		Project project = fixtures.project();
		Environment environment = fixtures.environment(project, Map.of());
		TestSuite suite = fixtures.suite(project);
		long a = createCase(suite, "a");
		long b = createCase(suite, "b");
		long c = createCase(suite, "c");

		assertThat(patch("/api/suites/" + suite.getId(), """
				{"caseOrder": [%d, %d, %d]}""".formatted(c, a, b))).hasStatusOk()
			.bodyJson()
			.extractingPath("$.caseCount")
			.isEqualTo(3);
		// 새 케이스는 마지막
		long d = createCase(suite, "d");

		assertThat(mvc.get().uri("/api/cases?suiteId={id}", suite.getId())).bodyJson()
			.extractingPath("$[*].id")
			.isEqualTo(List.of((int) c, (int) a, (int) b, (int) d));
		post("/api/runs", """
				{"suiteId": %d, "environmentId": %d}""".formatted(suite.getId(), environment.getId()));
		assertThat(executor.requests()).extracting(request -> request.uri().getPath())
			.containsExactly("/c", "/a", "/b", "/d");
	}

	@Test
	void placesANewCaseRightAfterTheGivenCase() {
		TestSuite suite = fixtures.suite(fixtures.project());
		long a = createCase(suite, "a");
		long b = createCase(suite, "b");
		long c = createCase(suite, "c");

		MvcTestResult copy = post("/api/cases", """
				{"suiteId": %d, "name": "a (copy)", "request": {"method": "GET", "path": "/a"}, "afterCaseId": %d}"""
			.formatted(suite.getId(), a));
		// 마지막 케이스 뒤도 가능
		MvcTestResult last = post("/api/cases", """
				{"suiteId": %d, "name": "c (copy)", "request": {"method": "GET", "path": "/c"}, "afterCaseId": %d}"""
			.formatted(suite.getId(), c));

		assertThat(copy).hasStatus(HttpStatus.CREATED);
		assertThat(mvc.get().uri("/api/cases?suiteId={id}", suite.getId())).bodyJson()
			.extractingPath("$[*].id")
			.isEqualTo(List.of((int) a, (int) idOf(copy), (int) b, (int) c, (int) idOf(last)));
	}

	@Test
	void rejectsPlacingAfterACaseOfAnotherSuiteOrAMissingOne() {
		Project project = fixtures.project();
		TestSuite suite = fixtures.suite(project);
		long other = createCase(fixtures.suite(project), "other");
		String json = """
				{"suiteId": %d, "name": "x", "request": {"method": "GET", "path": "/x"}, "afterCaseId": %d}""";

		assertThat(post("/api/cases", json.formatted(suite.getId(), other))).hasStatus(HttpStatus.BAD_REQUEST)
			.bodyJson()
			.extractingPath("$.detail")
			.asString()
			.contains("is not a case of test suite");
		assertThat(post("/api/cases", json.formatted(suite.getId(), -1))).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(mvc.get().uri("/api/cases?suiteId={id}", suite.getId())).bodyJson()
			.extractingPath("$.length()")
			.isEqualTo(0);
	}

	@Test
	void rejectsCaseOrdersThatDoNotMatchTheCurrentCases() {
		Project project = fixtures.project();
		TestSuite suite = fixtures.suite(project);
		long a = createCase(suite, "a");
		long b = createCase(suite, "b");
		long other = createCase(fixtures.suite(project), "other");
		long archived = createCase(suite, "archived");
		mvc.delete().uri("/api/cases/{id}", archived).exchange();
		String uri = "/api/suites/" + suite.getId();

		// 그사이 추가·삭제된 케이스 (빠짐, 다른 스위트, 삭제된 케이스)
		for (String order : List.of("[%d]".formatted(a), "[%d, %d, %d]".formatted(a, b, other),
				"[%d, %d, %d]".formatted(a, b, archived))) {
			assertThat(patch(uri, "{\"caseOrder\": " + order + "}")).hasStatus(HttpStatus.CONFLICT)
				.bodyJson()
				.extractingPath("$.detail")
				.asString()
				.contains("reload");
		}
		// 중복·null
		assertThat(patch(uri, """
				{"caseOrder": [%d, %d, %d]}""".formatted(a, b, a))).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(patch(uri, """
				{"caseOrder": [%d, null]}""".formatted(a))).hasStatus(HttpStatus.BAD_REQUEST);
		// 거부되면 함께 보낸 이름 변경도 반영하지 않음
		assertThat(patch(uri, """
				{"name": "renamed", "caseOrder": [%d]}""".formatted(b))).hasStatus(HttpStatus.CONFLICT);
		assertThat(mvc.get().uri(uri)).bodyJson().extractingPath("$.name").isEqualTo(suite.getName());
		assertThat(mvc.get().uri("/api/cases?suiteId={id}", suite.getId())).bodyJson()
			.extractingPath("$[*].id")
			.isEqualTo(List.of((int) a, (int) b));
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

	private static String extractorCaseJson(long suiteId, String name, String request, String extractors) {
		return """
				{"suiteId": %d, "name": "%s", "request": %s, "extractors": %s}""".formatted(suiteId, name, request,
				extractors);
	}

	private long createCase(TestSuite suite, String name) {
		String location = post("/api/cases", caseJson(suite.getId(), name, """
				{"method": "GET", "path": "/%s"}""".formatted(name))).getResponse().getHeader("Location");
		return Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
	}

	private static long idOf(MvcTestResult created) {
		String location = created.getResponse().getHeader("Location");
		return Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
	}

}
