package com.sentinel.api;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import com.sentinel.TestAuthConfiguration;
import com.sentinel.TestcontainersConfiguration;
import com.sentinel.application.FakeRequestExecutorConfiguration;
import com.sentinel.application.MutableClock;
import com.sentinel.application.auth.AccessTokenService;
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

	@Autowired
	private AccessTokenService accessTokens;

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

		MvcTestResult invited = post("/api/invites", adminToken, """
				{"email": "%s", "role": "MEMBER"}""".formatted(email));

		assertThat(invited).hasStatus(HttpStatus.CREATED);
		String token = read(invited, "$.token");
		assertThat(invited.getResponse().getHeader("Location")).isEqualTo("/api/invites/" + token);
		// 저장은 해시로만
		assertThat(tokens.findAll()).singleElement()
			.satisfies(saved -> assertThat(saved.getTokenHash()).isNotEqualTo(token).hasSize(64));

		assertThat(mvc.get().uri("/api/invites/{token}", token)).hasStatusOk()
			.bodyJson()
			.extractingPath("$.email")
			.isEqualTo(email);

		// 초대 수락 = 계정 만들기 (로그인 없이, 초대 토큰으로)
		MvcTestResult accepted = post("/api/users", null, """
				{"inviteToken": "%s", "name": "구성원", "password": "member-password"}""".formatted(token));
		assertThat(accepted).hasStatus(HttpStatus.CREATED);
		assertThat(accepted).bodyJson().extractingPath("$.email").isEqualTo(email);
		assertThat(accepted).bodyJson().extractingPath("$.role").isEqualTo("MEMBER");
		assertThat(accepted.getResponse().getHeader("Location"))
			.isEqualTo("/api/users/" + ((Number) JsonPath.read(body(accepted), "$.id")).longValue());

		// 만든 계정으로 로그인
		assertThat(get("/api/projects", authService.login(email, "member-password").token())).hasStatusOk();
	}

	@Test
	void refusesToUseAnInviteTwiceOrAfterItExpired() {
		String token = read(post("/api/invites", adminToken, """
				{"email": "member-%s@sentinel.test"}""".formatted(UUID.randomUUID())), "$.token");
		post("/api/users", null, """
				{"inviteToken": "%s", "name": "구성원", "password": "member-password"}""".formatted(token));

		assertThat(post("/api/users", null, """
				{"inviteToken": "%s", "name": "다시", "password": "member-password"}""".formatted(token)))
			.hasStatus(HttpStatus.NOT_FOUND);
		assertThat(post("/api/users", null, """
				{"inviteToken": "forged", "name": "위조", "password": "member-password"}""")).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(post("/api/users", null, """
				{"name": "토큰 없음", "password": "member-password"}""")).hasStatus(HttpStatus.BAD_REQUEST);

		String second = read(post("/api/invites", adminToken, """
				{"email": "later-%s@sentinel.test"}""".formatted(UUID.randomUUID())), "$.token");
		clock.advance(Duration.ofDays(8));
		assertThat(mvc.get().uri("/api/invites/{token}", second)).hasStatus(HttpStatus.NOT_FOUND);
	}

	@Test
	void rejectsInvitesForExistingAccountsAndWeakPasswords() {
		String email = users.findById(adminId).orElseThrow().getEmail();

		assertThat(post("/api/invites", adminToken, """
				{"email": "%s"}""".formatted(email))).hasStatus(HttpStatus.CONFLICT);

		String token = read(post("/api/invites", adminToken, """
				{"email": "member-%s@sentinel.test"}""".formatted(UUID.randomUUID())), "$.token");
		assertThat(post("/api/users", null, """
				{"inviteToken": "%s", "name": "구성원", "password": "short"}""".formatted(token)))
			.hasStatus(HttpStatus.BAD_REQUEST)
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

		MvcTestResult issued = post("/api/password-resets", adminToken, """
				{"userId": %d}""".formatted(member.getId()));
		assertThat(issued).hasStatus(HttpStatus.CREATED);
		String token = read(issued, "$.token");
		assertThat(issued.getResponse().getHeader("Location")).isEqualTo("/api/password-resets/" + token);
		var link = assertThat(mvc.get().uri("/api/password-resets/{token}", token)).hasStatusOk().bodyJson();
		link.extractingPath("$.user.name").isEqualTo(email);
		link.extractingPath("$.user.id").isEqualTo(member.getId().intValue());

		// 다른 사용자의 비밀번호에는 쓸 수 없음 (위조와 같은 404)
		assertThat(put("/api/users/" + adminId + "/password", null, """
				{"resetToken": "%s", "password": "brand-new-password"}""".formatted(token)))
			.hasStatus(HttpStatus.NOT_FOUND);
		assertThat(put("/api/users/" + member.getId() + "/password", null, """
				{"resetToken": "%s", "password": "brand-new-password"}""".formatted(token)))
			.hasStatus(HttpStatus.NO_CONTENT);

		// 기존 세션은 모두 종료되고 새 비밀번호로만 로그인
		assertThat(get("/api/projects", memberToken)).hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(authService.login(email, "brand-new-password").token()).isNotBlank();
		assertThat(put("/api/users/" + member.getId() + "/password", null, """
				{"resetToken": "%s", "password": "another-password"}""".formatted(token)))
			.hasStatus(HttpStatus.NOT_FOUND);
		// 링크 발급은 관리자만
		assertThat(post("/api/password-resets", authService.login(email, "brand-new-password").token(), """
				{"userId": %d}""".formatted(member.getId()))).hasStatus(HttpStatus.FORBIDDEN);
	}

	@Test
	void acceptingAnInviteVoidsTheOtherInvitesForTheSameEmail() {
		// 관리자가 초대를 다시 보냈고 두 번째 링크로 가입. 첫 링크가 남아 있으면 계정 삭제 뒤 다시 가입할 수 있음
		String email = "member-" + UUID.randomUUID() + "@sentinel.test";
		String first = read(post("/api/invites", adminToken, """
				{"email": "%s", "role": "ADMIN"}""".formatted(email)), "$.token");
		String second = read(post("/api/invites", adminToken, """
				{"email": "%s", "role": "MEMBER"}""".formatted(email)), "$.token");

		assertThat(post("/api/users", null, """
				{"inviteToken": "%s", "name": "구성원", "password": "member-password"}""".formatted(second)))
			.hasStatus(HttpStatus.CREATED);

		assertThat(mvc.get().uri("/api/invites/{token}", first)).hasStatus(HttpStatus.NOT_FOUND);
		long memberId = users.findByEmailAndArchivedAtIsNull(email).orElseThrow().getId();
		assertThat(mvc.delete().uri("/api/users/{id}", memberId).header("Authorization", "Bearer " + adminToken))
			.hasStatus(HttpStatus.NO_CONTENT);
		assertThat(post("/api/users", null, """
				{"inviteToken": "%s", "name": "다시", "password": "member-password"}""".formatted(first)))
			.hasStatus(HttpStatus.NOT_FOUND);
	}

	@Test
	void deletingAUserVoidsThePendingInvitesForTheirEmail() {
		String email = "member-" + UUID.randomUUID() + "@sentinel.test";
		// 초대를 보낸 뒤 그 이메일의 계정이 초대 수락 없이 따로 생긴 경우 (초대는 남아 있음)
		String leftover = read(post("/api/invites", adminToken, """
				{"email": "%s", "role": "ADMIN"}""".formatted(email)), "$.token");
		User member = users.save(new User(email, "구성원", TestAuthConfiguration.passwordHash("member-password"), User.Role.MEMBER));

		assertThat(mvc.delete().uri("/api/users/{id}", member.getId()).header("Authorization", "Bearer " + adminToken))
			.hasStatus(HttpStatus.NO_CONTENT);

		assertThat(mvc.get().uri("/api/invites/{token}", leftover)).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(tokens.findAll()).noneMatch(token -> email.equals(token.getEmail()));
	}

	@Test
	void resettingAPasswordRevokesApiTokensButChangingItKeepsThem() {
		String email = "member-" + UUID.randomUUID() + "@sentinel.test";
		User member = users.save(new User(email, "구성원", TestAuthConfiguration.passwordHash("old-password-1"), User.Role.MEMBER));
		String apiToken = accessTokens.issue(member.getId(), "ci", 30).token();
		String kept = accessTokens.issue(adminId, "admin ci", 30).token();

		// 본인 비밀번호 변경은 API 토큰 유지 (CI가 갑자기 끊기지 않도록)
		assertThat(put("/api/users/me/password", adminToken, """
				{"currentPassword": "%s", "newPassword": "new-password-1234"}""".formatted(ADMIN_PASSWORD)))
			.hasStatus(HttpStatus.NO_CONTENT);
		assertThat(accessTokens.authenticate(kept)).isPresent();

		// 재설정 링크(계정 복구)는 그 사용자의 API 토큰도 폐기
		String admin = authService.login(users.findById(adminId).orElseThrow().getEmail(), "new-password-1234").token();
		String link = read(post("/api/password-resets", admin, """
				{"userId": %d}""".formatted(member.getId())), "$.token");
		assertThat(accessTokens.authenticate(apiToken)).isPresent();
		assertThat(put("/api/users/" + member.getId() + "/password", null, """
				{"resetToken": "%s", "password": "brand-new-password"}""".formatted(link)))
			.hasStatus(HttpStatus.NO_CONTENT);

		assertThat(accessTokens.authenticate(apiToken)).isEmpty();
		assertThat(accessTokens.authenticate(kept)).isPresent();
	}

	@Test
	void changesOwnPasswordAndEndsOtherSessions() {
		String otherDevice = authService.login(users.findById(adminId).orElseThrow().getEmail(), ADMIN_PASSWORD)
			.token();

		assertThat(put("/api/users/me/password", adminToken, """
				{"currentPassword": "wrong-password", "newPassword": "new-password-1234"}"""))
			.hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(put("/api/users/me/password", adminToken, """
				{"currentPassword": "%s", "newPassword": "short"}""".formatted(ADMIN_PASSWORD)))
			.hasStatus(HttpStatus.BAD_REQUEST);

		assertThat(put("/api/users/me/password", adminToken, """
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
		assertThat(post("/api/invites", memberToken, """
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

	private MvcTestResult put(String uri, String token, String body) {
		var request = mvc.put().uri(uri).contentType(MediaType.APPLICATION_JSON).content(body);
		return (token == null ? request : request.header("Authorization", "Bearer " + token)).exchange();
	}

	private static String body(MvcTestResult result) {
		try {
			return result.getResponse().getContentAsString();
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
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
