package com.sentinel.api;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.jayway.jsonpath.JsonPath;
import com.sentinel.TestAuthConfiguration;
import com.sentinel.TestcontainersConfiguration;
import com.sentinel.application.FakeRequestExecutorConfiguration;
import com.sentinel.application.TestFixtures;
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
 * 설정 변경 이력. 등록·수정·삭제 API가 남기는 기록과 조회 조건
 */
@SpringBootTest(properties = { "sentinel.schedule.enabled=false",
		"sentinel.secret.key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=" })
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, FakeRequestExecutorConfiguration.class, TestFixtures.class,
		TestAuthConfiguration.class })
class ChangeLogApiTest {

	@Autowired
	private MockMvcTester mvc;

	@Autowired
	private TestFixtures fixtures;

	@AfterEach
	void cleanUp() {
		fixtures.deleteAll();
	}

	@Test
	void recordsWhoChangedWhichFieldsOfACase() {
		long project = id(post("/api/projects", "{\"name\": \"billing\"}"));
		long suite = id(post("/api/suites", "{\"projectId\": %d, \"name\": \"smoke\"}".formatted(project)));
		String casePath = location(post("/api/cases", """
				{"suiteId": %d, "name": "health", "request": {"method": "GET", "path": "/health"}}""".formatted(suite)));

		patch(casePath, """
				{"name": "health check", "assertions": [{"type": "statusCode", "anyOf": ["200"]}],
				 "request": {"method": "GET", "path": "/health"}}""");
		// 같은 값으로 저장하면 기록하지 않음
		patch(casePath, "{\"name\": \"health check\"}");
		mvc.delete().uri(casePath).exchange();

		String logs = logs("targetType=CASE&targetId=" + idOf(casePath));
		assertThat(JsonPath.<List<String>>read(logs, "$.content[*].action")).containsExactly("DELETED", "UPDATED",
				"CREATED");
		// 요청 정의는 같은 값이라 바뀐 항목에서 제외
		assertThat(JsonPath.<List<String>>read(logs, "$.content[1].fields")).containsExactly("name", "assertions");
		assertThat(JsonPath.<String>read(logs, "$.content[1].target.name")).isEqualTo("health check");
		assertThat(JsonPath.<Integer>read(logs, "$.content[0].project.id")).isEqualTo((int) project);
		assertThat(JsonPath.<String>read(logs, "$.content[0].project.name")).isNotBlank();
		assertThat(JsonPath.<String>read(logs, "$.content[0].actor.name")).startsWith("tester-");
		assertThat(JsonPath.<String>read(logs, "$.content[0].occurredAt")).isNotBlank();
	}

	@Test
	void recordsVariableAndSecretNamesWithoutValues() {
		long project = id(post("/api/projects", "{\"name\": \"billing\"}"));
		long environment = id(post("/api/environments", """
				{"projectId": %d, "name": "dev", "baseUrl": "https://gw.dev.internal",
				 "variables": {"userId": "user-before", "region": "region-before", "locale": "ko"}}""".formatted(project)));

		patch("/api/environments/" + environment, """
				{"baseUrl": "https://gw.stg.internal", "variables": {"userId": "user-after", "tenant": "tenant-after", "locale": "ko"},
				 "secrets": {"token": "top-secret-value"}}""");

		String logs = logs("targetType=ENVIRONMENT&targetId=" + environment);
		// 변수는 키별로 (바뀜·삭제·추가, 그대로인 locale 제외), 시크릿은 이름만
		assertThat(JsonPath.<List<String>>read(logs, "$.content[0].fields")).containsExactly("baseUrl",
				"variables.region", "variables.tenant", "variables.userId", "secrets.token");
		assertThat(logs).doesNotContain("top-secret-value", "gw.stg.internal", "user-before", "user-after",
				"region-before", "tenant-after");
	}

	@Test
	void recordsCaseOrderChangesOnTheSuite() {
		long project = id(post("/api/projects", "{\"name\": \"billing\"}"));
		long suite = id(post("/api/suites", "{\"projectId\": %d, \"name\": \"smoke\"}".formatted(project)));
		long first = createCase(suite, "a");
		long second = createCase(suite, "b");

		patch("/api/suites/" + suite, "{\"caseOrder\": [%d, %d]}".formatted(first, second));
		patch("/api/suites/" + suite, "{\"caseOrder\": [%d, %d]}".formatted(second, first));

		String logs = logs("targetType=SUITE&targetId=" + suite);
		// 순서가 그대로인 저장은 기록하지 않음
		assertThat(JsonPath.<List<String>>read(logs, "$.content[*].action")).containsExactly("UPDATED", "CREATED");
		assertThat(JsonPath.<List<String>>read(logs, "$.content[0].fields")).containsExactly("caseOrder");
	}

	@Test
	void recordsScheduleChanges() {
		long project = id(post("/api/projects", "{\"name\": \"billing\"}"));
		long environment = id(post("/api/environments", """
				{"projectId": %d, "name": "dev", "baseUrl": "https://gw.dev.internal"}""".formatted(project)));
		long suite = id(post("/api/suites", "{\"projectId\": %d, \"name\": \"smoke\"}".formatted(project)));
		long schedule = id(post("/api/schedules", """
				{"suiteId": %d, "environmentId": %d, "cron": "10 9 * * 1-5"}""".formatted(suite, environment)));

		patch("/api/schedules/" + schedule, "{\"enabled\": false, \"cron\": \"0 18 * * *\"}");
		patch("/api/schedules/" + schedule, "{\"skipHolidays\": true}");
		// 바뀐 것이 없으면 기록하지 않음
		patch("/api/schedules/" + schedule, "{\"skipHolidays\": true}");
		mvc.delete().uri("/api/schedules/{id}", schedule).exchange();

		String logs = logs("targetType=SCHEDULE&targetId=" + schedule);
		assertThat(JsonPath.<List<String>>read(logs, "$.content[*].action")).containsExactly("DELETED", "UPDATED",
				"UPDATED", "CREATED");
		assertThat(JsonPath.<List<String>>read(logs, "$.content[1].fields")).containsExactly("skipHolidays");
		assertThat(JsonPath.<List<String>>read(logs, "$.content[2].fields")).containsExactly("cron", "enabled");
		// 스케줄은 스위트 이름으로 표시
		assertThat(JsonPath.<String>read(logs, "$.content[0].target.name")).isEqualTo("smoke");
	}

	@Test
	void keepsNoRecordWhenTheChangeIsRejected() {
		long project = id(post("/api/projects", "{\"name\": \"billing\"}"));
		post("/api/projects", "{\"name\": \"payments\"}");

		assertThat(patch("/api/projects/" + project, "{\"name\": \"payments\"}")).hasStatus(HttpStatus.CONFLICT);
		assertThat(patch("/api/projects/" + project, "{\"name\": \" \"}")).hasStatus(HttpStatus.BAD_REQUEST);

		assertThat(JsonPath.<List<String>>read(logs("targetType=PROJECT&targetId=" + project), "$.content[*].action"))
			.containsExactly("CREATED");
	}

	@Test
	void recordsOnlyTheDeletedItemWhenDeletionCascades() {
		long project = id(post("/api/projects", "{\"name\": \"billing\"}"));
		long suite = id(post("/api/suites", "{\"projectId\": %d, \"name\": \"smoke\"}".formatted(project)));
		createCase(suite, "a");

		mvc.delete().uri("/api/projects/{id}", project).exchange();

		String logs = logs("projectId=" + project);
		assertThat(JsonPath.<List<String>>read(logs, "$.content[*].target.type")).containsExactly("PROJECT", "CASE",
				"SUITE", "PROJECT");
		assertThat(JsonPath.<List<String>>read(logs, "$.content[*].action")).containsExactly("DELETED", "CREATED",
				"CREATED", "CREATED");
	}

	@Test
	void filtersByProjectAndPages() {
		long billing = id(post("/api/projects", "{\"name\": \"billing\"}"));
		long payments = id(post("/api/projects", "{\"name\": \"payments\"}"));
		post("/api/suites", "{\"projectId\": %d, \"name\": \"smoke\"}".formatted(billing));
		post("/api/suites", "{\"projectId\": %d, \"name\": \"smoke\"}".formatted(payments));

		String billingLogs = logs("projectId=" + billing);
		assertThat(JsonPath.<List<String>>read(billingLogs, "$.content[*].target.name")).containsExactly("smoke",
				"billing");
		assertThat(JsonPath.<Integer>read(billingLogs, "$.totalElements")).isEqualTo(2);

		String firstPage = logs("size=3");
		assertThat(JsonPath.<Integer>read(firstPage, "$.totalElements")).isEqualTo(4);
		assertThat(JsonPath.<Integer>read(firstPage, "$.totalPages")).isEqualTo(2);
		assertThat(mvc.get().uri("/api/change-logs?targetType=UNKNOWN")).hasStatus(HttpStatus.BAD_REQUEST);
	}

	@Test
	void filtersBySuiteIncludingItsCasesAndSchedules() {
		long project = id(post("/api/projects", "{\"name\": \"billing\"}"));
		long environment = id(post("/api/environments", """
				{"projectId": %d, "name": "dev", "baseUrl": "https://gw.dev.internal"}""".formatted(project)));
		long smoke = id(post("/api/suites", "{\"projectId\": %d, \"name\": \"smoke\"}".formatted(project)));
		long other = id(post("/api/suites", "{\"projectId\": %d, \"name\": \"other\"}".formatted(project)));
		long health = createCase(smoke, "health");
		createCase(other, "elsewhere");
		post("/api/schedules", """
				{"suiteId": %d, "environmentId": %d, "cron": "0 9 * * *"}""".formatted(smoke, environment));
		patch("/api/cases/" + health, "{\"name\": \"health check\"}");

		String logs = logs("suiteId=" + smoke);

		// 스위트 자신과 소속 케이스·스케줄만 (다른 스위트, 프로젝트·환경 제외)
		assertThat(JsonPath.<List<String>>read(logs, "$.content[*].target.type")).containsExactly("CASE", "SCHEDULE",
				"CASE", "SUITE");
		assertThat(JsonPath.<List<String>>read(logs, "$.content[*].target.name")).containsExactly("health check",
				"smoke", "health", "smoke");
		assertThat(JsonPath.<List<Integer>>read(logs, "$.content[*].suite.id")).containsOnly((int) smoke);
		assertThat(JsonPath.<Object>read(logs("targetType=PROJECT&targetId=" + project), "$.content[0].suite"))
			.isNull();
	}

	@Test
	void recordsInvitesSignUpsResetLinksAndDeletions() {
		String email = "member-" + System.nanoTime() + "@sentinel.test";
		String invite = JsonPath.read(body(post("/api/invites", """
				{"email": "%s", "role": "MEMBER"}""".formatted(email))), "$.token");
		long user = JsonPath.<Integer>read(body(post("/api/users", """
				{"inviteToken": "%s", "name": "구성원", "password": "member-password"}""".formatted(invite))), "$.id");
		String session = JsonPath.read(body(post("/api/sessions", """
				{"email": "%s", "password": "member-password"}""".formatted(email))), "$.token");
		// 본인 비밀번호 변경은 가입한 사용자의 세션으로
		assertThat(mvc.put()
			.uri("/api/users/me/password")
			.header("Authorization", "Bearer " + session)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"currentPassword": "member-password", "newPassword": "changed-member-password"}""")).hasStatus(
					HttpStatus.NO_CONTENT);
		String reset = JsonPath.read(body(post("/api/password-resets", "{\"userId\": %d}".formatted(user))), "$.token");
		assertThat(mvc.put()
			.uri("/api/users/{id}/password", user)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"resetToken\": \"%s\", \"password\": \"new-member-password\"}".formatted(reset)))
			.hasStatus(HttpStatus.NO_CONTENT);
		mvc.delete().uri("/api/users/{id}", user).exchange();

		String userLogs = logs("targetType=USER&targetId=" + user);
		assertThat(JsonPath.<List<String>>read(userLogs, "$.content[*].action")).containsExactly("DELETED", "UPDATED",
				"UPDATED", "UPDATED", "CREATED");
		assertThat(JsonPath.<List<List<String>>>read(userLogs, "$.content[*].fields")).containsExactly(List.of(),
				List.of("password"), List.of("passwordResetLink"), List.of("password"), List.of());
		// 가입·비밀번호 변경·재설정은 본인이 한 변경, 재설정 링크 발급·삭제는 관리자
		assertThat(JsonPath.<List<String>>read(userLogs, "$.content[*].actor.name")).satisfiesExactly(
				admin -> assertThat(admin).startsWith("tester-"), self -> assertThat(self).isEqualTo(email),
				admin -> assertThat(admin).startsWith("tester-"), self -> assertThat(self).isEqualTo(email),
				self -> assertThat(self).isEqualTo(email));
		assertThat(JsonPath.<List<Object>>read(userLogs, "$.content[*].project")).containsOnlyNulls();

		String inviteLogs = logs("targetType=INVITE");
		assertThat(JsonPath.<List<String>>read(inviteLogs, "$.content[*].target.name")).contains(email);
		assertThat(userLogs + inviteLogs).doesNotContain(invite, reset, "member-password",
				"changed-member-password", "new-member-password");
	}

	private long createCase(long suite, String name) {
		return idOf(location(post("/api/cases", """
				{"suiteId": %d, "name": "%s", "request": {"method": "GET", "path": "/%s"}}""".formatted(suite, name,
				name))));
	}

	private String logs(String query) {
		MvcTestResult result = mvc.get().uri("/api/change-logs?" + query).exchange();
		assertThat(result).hasStatusOk();
		return body(result);
	}

	private MvcTestResult post(String uri, String body) {
		return mvc.post().uri(uri).contentType(MediaType.APPLICATION_JSON).content(body).exchange();
	}

	private MvcTestResult patch(String uri, String body) {
		return mvc.patch().uri(uri).contentType(MediaType.APPLICATION_JSON).content(body).exchange();
	}

	private static String body(MvcTestResult result) {
		try {
			return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
		}
		catch (UnsupportedEncodingException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static String location(MvcTestResult result) {
		assertThat(result).hasStatus(HttpStatus.CREATED);
		return result.getResponse().getHeader("Location");
	}

	private static long id(MvcTestResult result) {
		return idOf(location(result));
	}

	private static long idOf(String location) {
		return Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
	}

}
