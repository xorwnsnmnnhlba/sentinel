package com.sentinel.api;

import java.io.UnsupportedEncodingException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import com.sentinel.TestcontainersConfiguration;
import com.sentinel.application.FakeRequestExecutorConfiguration;
import com.sentinel.application.MutableClock;
import com.sentinel.application.auth.AuthService;
import com.sentinel.domain.User;
import com.sentinel.infra.persistence.UserRepository;
import com.sentinel.infra.persistence.UserSessionRepository;
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
 * 로그인·로그아웃과 보호된 API 접근. 기본 로그인 설정(TestAuthConfiguration) 없이 직접 토큰을 다룬다.
 */
@SpringBootTest(properties = { "sentinel.schedule.enabled=false", "sentinel.auth.session-validity=1h" })
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, FakeRequestExecutorConfiguration.class, AuthTest.ClockConfiguration.class })
class AuthTest {

	private static final String PASSWORD = "correct-horse-battery-staple";

	@Autowired
	private MockMvcTester mvc;

	@Autowired
	private UserRepository users;

	@Autowired
	private UserSessionRepository sessions;

	@Autowired
	private AuthService authService;

	@Autowired
	private MutableClock clock;

	private String email;

	@BeforeEach
	void createUser() {
		clock.set(Instant.parse("2026-09-20T00:00:00Z"));
		email = "member-" + UUID.randomUUID() + "@sentinel.test";
		users.save(new User(email, "구성원", authService.encodePassword(PASSWORD), User.Role.MEMBER));
	}

	@AfterEach
	void cleanUp() {
		sessions.deleteAll();
		users.deleteAll();
	}

	@Test
	void rejectsRequestsWithoutAValidSession() {
		assertThat(mvc.get().uri("/api/projects")).hasStatus(HttpStatus.UNAUTHORIZED)
			.bodyJson()
			.extractingPath("$.detail")
			.isEqualTo("authentication required");
		assertThat(withToken("/api/projects", "not-a-token")).hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(mvc.post().uri("/api/runs").contentType(MediaType.APPLICATION_JSON).content("{}"))
			.hasStatus(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void logsInAndUsesTheTokenForProtectedApis() {
		MvcTestResult login = login(email, PASSWORD);

		assertThat(login).hasStatus(HttpStatus.CREATED);
		assertThat(login).bodyJson().extractingPath("$.user.email").isEqualTo(email);
		assertThat(login).bodyJson().extractingPath("$.user.role").isEqualTo("MEMBER");
		String token = tokenOf(login);

		assertThat(withToken("/api/projects", token)).hasStatusOk();
		assertThat(withToken("/api/sessions/current", token)).hasStatusOk()
			.bodyJson()
			.extractingPath("$.email")
			.isEqualTo(email);
	}

	@Test
	void doesNotRevealWhetherTheAccountExists() throws Exception {
		MvcTestResult wrongPassword = login(email, "wrong-password");
		MvcTestResult unknownAccount = login("nobody-" + UUID.randomUUID() + "@sentinel.test", PASSWORD);

		assertThat(wrongPassword).hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(unknownAccount).hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(wrongPassword.getResponse().getContentAsString())
			.isEqualTo(unknownAccount.getResponse().getContentAsString());
	}

	@Test
	void storesOnlyHashesOfPasswordAndToken() {
		String token = tokenOf(login(email, PASSWORD));

		User saved = users.findByEmailAndArchivedAtIsNull(email).orElseThrow();
		assertThat(saved.getPasswordHash()).startsWith("$2").doesNotContain(PASSWORD);
		assertThat(sessions.findAll()).singleElement().satisfies(session -> {
			assertThat(session.getTokenHash()).isNotEqualTo(token).hasSize(64);
			assertThat(session.getUserId()).isEqualTo(saved.getId());
		});
	}

	@Test
	void logoutMakesTheTokenUnusable() {
		String token = tokenOf(login(email, PASSWORD));

		assertThat(mvc.delete().uri("/api/sessions/current").header("Authorization", "Bearer " + token))
			.hasStatus(HttpStatus.NO_CONTENT);

		assertThat(withToken("/api/projects", token)).hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(sessions.findAll()).isEmpty();
	}

	@Test
	void expiresSessionsAfterTheConfiguredTime() {
		String token = tokenOf(login(email, PASSWORD));

		clock.advance(Duration.ofMinutes(59));
		assertThat(withToken("/api/projects", token)).hasStatusOk();

		clock.advance(Duration.ofMinutes(2));
		assertThat(withToken("/api/projects", token)).hasStatus(HttpStatus.UNAUTHORIZED);
		// 만료된 세션은 정리
		assertThat(sessions.findAll()).isEmpty();
	}

	@Test
	void stopsSessionsOfDeletedUsers() {
		String token = tokenOf(login(email, PASSWORD));
		User user = users.findByEmailAndArchivedAtIsNull(email).orElseThrow();
		user.archive(clock.instant());
		users.saveAndFlush(user);

		assertThat(withToken("/api/projects", token)).hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(sessions.findAll()).isEmpty();
	}

	private MvcTestResult login(String email, String password) {
		return mvc.post()
			.uri("/api/sessions")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\": \"%s\", \"password\": \"%s\"}".formatted(email, password))
			.exchange();
	}

	private MvcTestResult withToken(String uri, String token) {
		return mvc.get().uri(uri).header("Authorization", "Bearer " + token).exchange();
	}

	private static String tokenOf(MvcTestResult login) {
		try {
			return JsonPath.read(login.getResponse().getContentAsString(), "$.token");
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
			return new MutableClock(Instant.parse("2026-09-20T00:00:00Z"));
		}

	}

}
