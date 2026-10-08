package com.sentinel.api;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import com.sentinel.TestAuthConfiguration;
import com.sentinel.TestcontainersConfiguration;
import com.sentinel.application.FakeRequestExecutorConfiguration;
import com.sentinel.application.MutableClock;
import com.sentinel.application.auth.AuthService;
import com.sentinel.domain.User;
import com.sentinel.infra.persistence.UserRepository;
import com.sentinel.infra.persistence.UserSessionRepository;
import com.sentinel.infra.persistence.UserTokenRepository;
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
 * 초대·비밀번호 재설정·비밀번호 변경·사용자 삭제. 링크 토큰은 1회용이고 만료가 있으며 값은 저장하지 않음
 */
@SpringBootTest(properties = { "sentinel.schedule.enabled=false", "sentinel.auth.invite-validity=7d",
		"sentinel.auth.password-reset-validity=2h" })
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, FakeRequestExecutorConfiguration.class,
		UserManagementTest.ClockConfiguration.class })
class UserManagementTest {

	private static final String ADMIN_PASSWORD = "admin-password-1234";

	@Autowired
	private MockMvcTester mvc;

	@Autowired
	private UserRepository users;

	@Autowired
	private UserTokenRepository tokens;

	@Autowired
	private UserSessionRepository sessions;

	@Autowired
	private AuthService authService;

	@Autowired
	private MutableClock clock;

	private String adminToken;

	private long adminId;

	@BeforeEach
	void createAdmin() {
		clock.set(Instant.parse("2026-09-20T00:00:00Z"));
		String email = "admin-" + UUID.randomUUID() + "@sentinel.test";
		User admin = users.save(new User(email, "관리자", TestAuthConfiguration.passwordHash(ADMIN_PASSWORD), User.Role.ADMIN));
		adminId = admin.getId();
		adminToken = authService.login(email, ADMIN_PASSWORD).token();
	}

	@AfterEach
	void cleanUp() {
		tokens.deleteAll();
		sessions.deleteAll();
		users.deleteAll();
	}

	@Test
	void invitesAUserWhoThenSignsInWithTheLink() {
		String email = "member-" + UUID.randomUUID() + "@sentinel.test";

		MvcTestResult invited = post("/api/users/invites", adminToken, """
				{"email": "%s", "role": "MEMBER"}""".formatted(email));

		assertThat(invited).hasStatus(HttpStatus.CREATED);
		String token = read(invited, "$.token");
		// 저장은 해시로만
		assertThat(tokens.findAll()).singleElement()
			.satisfies(saved -> assertThat(saved.getTokenHash()).isNotEqualTo(token).hasSize(64));

		assertThat(mvc.get().uri("/api/invites/{token}", token)).hasStatusOk()
			.bodyJson()
			.extractingPath("$.email")
			.isEqualTo(email);

		MvcTestResult accepted = post("/api/invites/" + token, null, """
				{"name": "구성원", "password": "member-password"}""");
		assertThat(accepted).hasStatus(HttpStatus.CREATED);
		assertThat(accepted).bodyJson().extractingPath("$.user.email").isEqualTo(email);
		assertThat(accepted).bodyJson().extractingPath("$.user.role").isEqualTo("MEMBER");

		// 발급된 세션으로 바로 사용 가능
		assertThat(get("/api/projects", read(accepted, "$.token"))).hasStatusOk();
	}

	@Test
	void refusesToUseAnInviteTwiceOrAfterItExpired() {
		String token = read(post("/api/users/invites", adminToken, """
				{"email": "member-%s@sentinel.test"}""".formatted(UUID.randomUUID())), "$.token");
		post("/api/invites/" + token, null, """
				{"name": "구성원", "password": "member-password"}""");

		assertThat(post("/api/invites/" + token, null, """
				{"name": "다시", "password": "member-password"}""")).hasStatus(HttpStatus.NOT_FOUND);

		String second = read(post("/api/users/invites", adminToken, """
				{"email": "later-%s@sentinel.test"}""".formatted(UUID.randomUUID())), "$.token");
		clock.advance(Duration.ofDays(8));
		assertThat(mvc.get().uri("/api/invites/{token}", second)).hasStatus(HttpStatus.NOT_FOUND);
	}

	@Test
	void rejectsInvitesForExistingAccountsAndWeakPasswords() {
		String email = users.findById(adminId).orElseThrow().getEmail();

		assertThat(post("/api/users/invites", adminToken, """
				{"email": "%s"}""".formatted(email))).hasStatus(HttpStatus.CONFLICT);

		String token = read(post("/api/users/invites", adminToken, """
				{"email": "member-%s@sentinel.test"}""".formatted(UUID.randomUUID())), "$.token");
		assertThat(post("/api/invites/" + token, null, """
				{"name": "구성원", "password": "short"}""")).hasStatus(HttpStatus.BAD_REQUEST)
			.bodyJson()
			.extractingPath("$.detail")
			.asString()
			.contains("at least 10 characters");
	}

	@Test
	void resetsAPasswordThroughAnAdminIssuedLink() {
		String email = "member-" + UUID.randomUUID() + "@sentinel.test";
		User member = users.save(new User(email, "구성원", TestAuthConfiguration.passwordHash("old-password-1"), User.Role.MEMBER));
		String memberToken = authService.login(email, "old-password-1").token();

		String token = read(post("/api/users/%d/password-resets".formatted(member.getId()), adminToken, ""), "$.token");
		assertThat(mvc.get().uri("/api/password-resets/{token}", token)).hasStatusOk()
			.bodyJson()
			.extractingPath("$.email")
			.isEqualTo(email);

		assertThat(post("/api/password-resets/" + token, null, """
				{"password": "brand-new-password"}""")).hasStatus(HttpStatus.NO_CONTENT);

		// 기존 세션은 모두 종료되고 새 비밀번호로만 로그인
		assertThat(get("/api/projects", memberToken)).hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(authService.login(email, "brand-new-password").token()).isNotBlank();
		assertThat(post("/api/password-resets/" + token, null, """
				{"password": "another-password"}""")).hasStatus(HttpStatus.NOT_FOUND);
	}

	@Test
	void changesOwnPasswordAndEndsOtherSessions() {
		String otherDevice = authService.login(users.findById(adminId).orElseThrow().getEmail(), ADMIN_PASSWORD)
			.token();

		assertThat(post("/api/users/me/password", adminToken, """
				{"currentPassword": "wrong-password", "newPassword": "new-password-1234"}"""))
			.hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(post("/api/users/me/password", adminToken, """
				{"currentPassword": "%s", "newPassword": "short"}""".formatted(ADMIN_PASSWORD)))
			.hasStatus(HttpStatus.BAD_REQUEST);

		assertThat(post("/api/users/me/password", adminToken, """
				{"currentPassword": "%s", "newPassword": "new-password-1234"}""".formatted(ADMIN_PASSWORD)))
			.hasStatus(HttpStatus.NO_CONTENT);

		assertThat(get("/api/projects", otherDevice)).hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(sessions.findAll()).isEmpty();
	}

	@Test
	void limitsUserManagementToAdministrators() {
		String email = "member-" + UUID.randomUUID() + "@sentinel.test";
		users.save(new User(email, "구성원", TestAuthConfiguration.passwordHash("member-password"), User.Role.MEMBER));
		String memberToken = authService.login(email, "member-password").token();

		assertThat(get("/api/users", memberToken)).hasStatus(HttpStatus.FORBIDDEN);
		assertThat(get("/api/users/" + adminId, memberToken)).hasStatus(HttpStatus.FORBIDDEN);
		assertThat(post("/api/users/invites", memberToken, """
				{"email": "other@sentinel.test"}""")).hasStatus(HttpStatus.FORBIDDEN);
		assertThat(get("/api/users", adminToken)).hasStatusOk().bodyJson().extractingPath("$.length()").isEqualTo(2);
	}

	@Test
	void deletesUsersButKeepsAtLeastOneAdministrator() {
		String email = "member-" + UUID.randomUUID() + "@sentinel.test";
		User member = users.save(new User(email, "구성원", TestAuthConfiguration.passwordHash("member-password"), User.Role.MEMBER));
		String memberToken = authService.login(email, "member-password").token();

		assertThat(mvc.delete().uri("/api/users/{id}", adminId).header("Authorization", "Bearer " + adminToken))
			.hasStatus(HttpStatus.BAD_REQUEST)
			.bodyJson()
			.extractingPath("$.detail")
			.asString()
			.contains("your own account");

		var read = assertThat(get("/api/users/" + member.getId(), adminToken)).hasStatusOk().bodyJson();
		read.extractingPath("$.email").isEqualTo(email);
		read.extractingPath("$.role").isEqualTo("MEMBER");
		assertThat(mvc.delete().uri("/api/users/{id}", member.getId()).header("Authorization", "Bearer " + adminToken))
			.hasStatus(HttpStatus.NO_CONTENT);
		assertThat(get("/api/users/" + member.getId(), adminToken)).hasStatus(HttpStatus.NOT_FOUND)
			.bodyJson()
			.extractingPath("$.detail")
			.isEqualTo("user " + member.getId() + " not found");
		assertThat(get("/api/projects", memberToken)).hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(get("/api/users", adminToken)).bodyJson().extractingPath("$.length()").isEqualTo(1);
	}

	private MvcTestResult post(String uri, String token, String body) {
		var request = mvc.post().uri(uri).contentType(MediaType.APPLICATION_JSON).content(body.isEmpty() ? "{}" : body);
		return (token == null ? request : request.header("Authorization", "Bearer " + token)).exchange();
	}

	private MvcTestResult get(String uri, String token) {
		return mvc.get().uri(uri).header("Authorization", "Bearer " + token).exchange();
	}

	private static String read(MvcTestResult result, String path) {
		try {
			return JsonPath.read(result.getResponse().getContentAsString(), path);
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class ClockConfiguration {

		@Bean
		@Primary
		MutableClock mutableClock() {
			return new MutableClock(Instant.parse("2026-09-20T00:00:00Z"));
		}

	}

}
