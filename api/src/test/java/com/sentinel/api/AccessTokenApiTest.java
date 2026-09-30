package com.sentinel.api;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import com.sentinel.TestcontainersConfiguration;
import com.sentinel.application.FakeRequestExecutorConfiguration;
import com.sentinel.application.InlineBackgroundRunsConfiguration;
import com.sentinel.application.MutableClock;
import com.sentinel.application.TestFixtures;
import com.sentinel.application.auth.AuthService;
import com.sentinel.domain.Environment;
import com.sentinel.domain.TestSuite;
import com.sentinel.domain.User;
import com.sentinel.infra.persistence.AccessTokenRepository;
import com.sentinel.infra.persistence.UserRepository;
import com.sentinel.runner.HttpMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CI용 API 토큰. 로그인 세션으로 발급·폐기하고, 토큰으로는 실행 시작·결과 조회만
 */
@SpringBootTest(properties = "sentinel.schedule.enabled=false")
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, FakeRequestExecutorConfiguration.class,
		InlineBackgroundRunsConfiguration.class, TestFixtures.class, AccessTokenApiTest.ClockConfiguration.class })
class AccessTokenApiTest {

	private static final Instant NOW = Instant.parse("2026-09-30T00:00:00Z");

	private static final String PASSWORD = "correct-horse-battery-staple";

	@Autowired
	private MockMvcTester mvc;

	@Autowired
	private UserRepository users;

	@Autowired
	private AccessTokenRepository accessTokens;

	@Autowired
	private AuthService authService;

	@Autowired
	private TestFixtures fixtures;

	@Autowired
	private MutableClock clock;

	private User user;

	private String session;

	@BeforeEach
	void logIn() {
		clock.set(NOW);
		user = users.save(new User("ci-" + UUID.randomUUID() + "@sentinel.test", "배포 담당",
				authService.encodePassword(PASSWORD), User.Role.MEMBER));
		session = authService.login(user.getEmail(), PASSWORD).token();
	}

	@AfterEach
	void cleanUp() {
		accessTokens.deleteAll();
		fixtures.deleteAll();
	}

	@Test
	void issuesATokenOnlyOnceAndListsItWithoutTheSecret() {
		MvcTestResult created = issue(session, "GitHub Actions 배포 확인", 90);

		assertThat(created).hasStatus(HttpStatus.CREATED);
		String token = JsonPath.read(body(created), "$.token");
		assertThat(token).startsWith("snt_").hasSize(47);
		var item = assertThat(created).bodyJson();
		item.extractingPath("$.accessToken.name").isEqualTo("GitHub Actions 배포 확인");
		item.extractingPath("$.accessToken.prefix").isEqualTo(token.substring(0, 8));
		item.extractingPath("$.accessToken.expiresAt").isEqualTo("2026-12-29T00:00:00Z");
		item.extractingPath("$.accessToken.lastUsedAt").isNull();

		MvcTestResult listed = get(session, "/api/access-tokens");
		assertThat(listed).bodyJson().extractingPath("$[0].name").isEqualTo("GitHub Actions 배포 확인");
		assertThat(body(listed)).doesNotContain(token);
		// 원문은 저장하지 않음
		assertThat(accessTokens.findAll()).singleElement()
			.satisfies(stored -> assertThat(stored.getTokenHash()).isNotEqualTo(token).doesNotContain(token));
	}

	@Test
	void startsRunsAndReadsThemButNothingElse() {
		String token = tokenOf(issue(session, "CI", 30));
		TestSuite suite = fixtures.suite(fixtures.project());
		Environment environment = fixtures.environment(suite.getProject(), Map.of());
		fixtures.testCase(suite, HttpMethod.GET, "/health", null, null);

		MvcTestResult started = post(token, "/api/runs",
				"{\"suiteId\": %d, \"environmentId\": %d}".formatted(suite.getId(), environment.getId()));

		assertThat(started).hasStatus(HttpStatus.ACCEPTED);
		long runId = ((Number) JsonPath.read(body(started), "$.id")).longValue();
		var run = assertThat(get(token, "/api/runs/" + runId)).hasStatusOk().bodyJson();
		run.extractingPath("$.status").isEqualTo("COMPLETED");
		// 토큰을 발급한 사용자가 실행한 것으로 기록
		run.extractingPath("$.triggeredBy.id").isEqualTo(user.getId().intValue());

		for (String path : List.of("/api/runs", "/api/projects", "/api/environments?projectId=1", "/api/access-tokens",
				"/api/sessions/current", "/api/runs/" + runId + "/x")) {
			assertThat(get(token, path)).as(path)
				.hasStatus(HttpStatus.FORBIDDEN)
				.bodyJson()
				.extractingPath("$.detail")
				.isEqualTo("access tokens can only start runs and read them");
		}
		assertThat(post(token, "/api/access-tokens", "{\"name\": \"more\", \"validityDays\": 30}"))
			.hasStatus(HttpStatus.FORBIDDEN);
		assertThat(post(token, "/api/projects", "{\"name\": \"x\"}")).hasStatus(HttpStatus.FORBIDDEN);
		assertThat(mvc.patch()
			.uri("/api/runs/{id}", runId)
			.header("Authorization", "Bearer " + token)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"status\": \"CANCELED\"}")).hasStatus(HttpStatus.FORBIDDEN);
		// 마지막 사용 시각
		assertThat(get(session, "/api/access-tokens")).bodyJson()
			.extractingPath("$[0].lastUsedAt")
			.isEqualTo("2026-09-30T00:00:00Z");
	}

	@Test
	void stopsWorkingWhenExpiredRevokedOrTheUserIsDeleted() {
		String expiring = tokenOf(issue(session, "expiring", 30));
		String revoked = tokenOf(issue(session, "revoked", 90));
		String orphan = tokenOf(issue(session, "orphan", 90));
		assertThat(get(expiring, "/api/runs/1")).hasStatus(HttpStatus.NOT_FOUND);

		clock.set(NOW.plus(Duration.ofDays(30)));
		assertThat(get(expiring, "/api/runs/1")).hasStatus(HttpStatus.UNAUTHORIZED);
		// 만료된 토큰은 목록에 남아 만료로 표시 (세션은 새로 로그인)
		session = authService.login(user.getEmail(), PASSWORD).token();
		assertThat(get(session, "/api/access-tokens")).bodyJson()
			.extractingPath("$[?(@.name == 'expiring')].expired")
			.isEqualTo(List.of(true));

		long revokedId = idOf("revoked");
		assertThat(mvc.delete().uri("/api/access-tokens/{id}", revokedId).header("Authorization", "Bearer " + session))
			.hasStatus(HttpStatus.NO_CONTENT);
		assertThat(get(revoked, "/api/runs/1")).hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(get(session, "/api/access-tokens")).bodyJson()
			.extractingPath("$[*].name")
			.isEqualTo(List.of("orphan", "expiring"));

		user.archive(clock.instant());
		users.save(user);
		assertThat(get(orphan, "/api/runs/1")).hasStatus(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void rejectsInvalidRequestsAndOtherUsersTokens() {
		assertThat(issue(session, "CI", 7)).hasStatus(HttpStatus.BAD_REQUEST)
			.bodyJson()
			.extractingPath("$.detail")
			.isEqualTo("validityDays must be one of 30, 90, 180, 365");
		assertThat(issue(session, " ", 30)).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(issue(session, "a".repeat(101), 30)).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(post(session, "/api/access-tokens", "{\"name\": \"CI\"}")).hasStatus(HttpStatus.BAD_REQUEST);

		issue(session, "mine", 30);
		long mine = idOf("mine");
		User other = users.save(new User("other-" + UUID.randomUUID() + "@sentinel.test", "다른 사람",
				authService.encodePassword(PASSWORD), User.Role.ADMIN));
		String otherSession = authService.login(other.getEmail(), PASSWORD).token();
		// 관리자라도 다른 사용자의 토큰은 보이지 않고 폐기할 수 없음
		assertThat(get(otherSession, "/api/access-tokens")).bodyJson().extractingPath("$.length()").isEqualTo(0);
		assertThat(mvc.delete().uri("/api/access-tokens/{id}", mine).header("Authorization", "Bearer " + otherSession))
			.hasStatus(HttpStatus.NOT_FOUND);
		assertThat(mvc.delete().uri("/api/access-tokens/{id}", mine).header("Authorization", "Bearer " + session))
			.hasStatus(HttpStatus.NO_CONTENT);
		assertThat(mvc.delete().uri("/api/access-tokens/{id}", mine).header("Authorization", "Bearer " + session))
			.hasStatus(HttpStatus.NOT_FOUND);
	}

	@Test
	void recordsIssuingAndRevokingWithoutTheToken() {
		String token = tokenOf(issue(session, "CI", 30));
		mvc.delete().uri("/api/access-tokens/{id}", idOf("CI")).header("Authorization", "Bearer " + session).exchange();

		MvcTestResult logs = get(session, "/api/change-logs?targetType=ACCESS_TOKEN");
		var history = assertThat(logs).hasStatusOk().bodyJson();
		history.extractingPath("$.content[*].action").isEqualTo(List.of("DELETED", "CREATED"));
		history.extractingPath("$.content[0].targetName").isEqualTo("CI");
		history.extractingPath("$.content[0].actor.id").isEqualTo(user.getId().intValue());
		assertThat(body(logs)).doesNotContain(token);
	}

	private MvcTestResult issue(String bearer, String name, int validityDays) {
		return post(bearer, "/api/access-tokens",
				"{\"name\": \"%s\", \"validityDays\": %d}".formatted(name, validityDays));
	}

	private long idOf(String name) {
		List<Integer> ids = JsonPath.read(body(get(session, "/api/access-tokens")),
				"$[?(@.name == '" + name + "')].id");
		return ids.getFirst();
	}

	private MvcTestResult get(String bearer, String path) {
		return mvc.get().uri(path).header("Authorization", "Bearer " + bearer).exchange();
	}

	private MvcTestResult post(String bearer, String path, String json) {
		return mvc.post()
			.uri(path)
			.header("Authorization", "Bearer " + bearer)
			.contentType(MediaType.APPLICATION_JSON)
			.content(json)
			.exchange();
	}

	private static String tokenOf(MvcTestResult result) {
		return JsonPath.read(body(result), "$.token");
	}

	private static String body(MvcTestResult result) {
		try {
			return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
		}
		catch (UnsupportedEncodingException ex) {
			throw new IllegalStateException(ex);
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class ClockConfiguration {

		@Bean
		@Primary
		MutableClock mutableClock() {
			return new MutableClock(NOW);
		}

	}

}
