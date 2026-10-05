package com.sentinel.api;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import com.sentinel.TestAuthConfiguration;
import com.sentinel.TestcontainersConfiguration;
import com.sentinel.application.FakeRequestExecutorConfiguration;
import com.sentinel.application.MutableClock;
import com.sentinel.application.TestFixtures;
import com.sentinel.application.auth.AuthService;
import com.sentinel.domain.User;
import com.sentinel.infra.persistence.UserRepository;
import org.junit.jupiter.api.AfterEach;
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

/** 휴일 등록·조회·삭제 ({@code /api/holidays}) */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, FakeRequestExecutorConfiguration.class, TestFixtures.class,
		TestAuthConfiguration.class, HolidayApiTest.ClockConfiguration.class })
class HolidayApiTest {

	/** 2027-01-01 00:30 KST (UTC로는 아직 2026-12-31) */
	private static final Instant NOW = Instant.parse("2026-12-31T15:30:00Z");

	@Autowired
	private MockMvcTester mvc;

	@Autowired
	private TestFixtures fixtures;

	@Autowired
	private UserRepository users;

	@Autowired
	private AuthService authService;

	@AfterEach
	void cleanUp() {
		fixtures.deleteAll();
	}

	@Test
	void createsAndListsHolidaysOfAYearInDateOrder() {
		MvcTestResult created = create("2026-10-09", " 한글날 ");
		create("2026-10-03", "개천절");
		create("2027-01-01", "신정");
		create("2026-12-31", "종무식");
		create("2026-01-01", "신정");

		assertThat(created).hasStatus(HttpStatus.CREATED);
		assertThat(created.getResponse().getHeader("Location")).isEqualTo("/api/holidays/" + id(created));
		var body = assertThat(created).bodyJson();
		body.extractingPath("$.date").isEqualTo("2026-10-09");
		body.extractingPath("$.name").isEqualTo("한글날");

		var year2026 = assertThat(mvc.get().uri("/api/holidays?year=2026")).hasStatusOk().bodyJson();
		year2026.extractingPath("$[*].name").isEqualTo(List.of("신정", "개천절", "한글날", "종무식"));
		year2026.extractingPath("$[1].date").isEqualTo("2026-10-03");
		// 연도 생략 시 올해. 서버 설정 시간대(Asia/Seoul) 기준이므로 2027
		assertThat(mvc.get().uri("/api/holidays")).bodyJson().extractingPath("$[*].name").isEqualTo(List.of("신정"));
		assertThat(mvc.get().uri("/api/holidays?year=2025")).bodyJson().extractingPath("$.length()").isEqualTo(0);
	}

	@Test
	void rejectsADuplicateDateUntilTheHolidayIsDeleted() {
		long chuseok = id(create("2026-09-25", "추석"));

		assertThat(create("2026-09-25", "추석 연휴")).hasStatus(HttpStatus.CONFLICT)
			.bodyJson()
			.extractingPath("$.detail")
			.isEqualTo("holiday 2026-09-25 already exists");

		assertThat(mvc.delete().uri("/api/holidays/{id}", chuseok)).hasStatus(HttpStatus.NO_CONTENT);
		assertThat(mvc.get().uri("/api/holidays?year=2026")).bodyJson().extractingPath("$.length()").isEqualTo(0);
		assertThat(mvc.delete().uri("/api/holidays/{id}", chuseok)).hasStatus(HttpStatus.NOT_FOUND)
			.bodyJson()
			.extractingPath("$.detail")
			.isEqualTo("holiday " + chuseok + " not found");
		// 삭제 후에는 같은 날짜 다시 등록 가능
		assertThat(create("2026-09-25", "추석 연휴")).hasStatus(HttpStatus.CREATED);
	}

	@Test
	void rejectsInvalidHolidays() {
		assertThat(create("2026-10-09", " ")).hasStatus(HttpStatus.BAD_REQUEST)
			.bodyJson()
			.extractingPath("$.detail")
			.isEqualTo("name must not be blank");
		assertThat(post("{\"date\": \"2026-10-09\"}")).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(create("2026-10-09", "가".repeat(101))).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(create("2026-10-09", "가".repeat(100))).hasStatus(HttpStatus.CREATED);
		assertThat(post("{\"name\": \"한글날\"}")).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(create("2026-13-01", "없는 날")).hasStatus(HttpStatus.BAD_REQUEST);
	}

	@Test
	void onlyAdministratorsCanCreateOrDelete() {
		long hangul = id(create("2026-10-09", "한글날"));
		String password = UUID.randomUUID().toString();
		User member = users.save(new User("member-" + UUID.randomUUID() + "@sentinel.test", "팀원",
				TestAuthConfiguration.passwordHash(password), User.Role.MEMBER));
		String session = "Bearer " + authService.login(member.getEmail(), password).token();

		assertThat(mvc.get().uri("/api/holidays?year=2026").header("Authorization", session)).hasStatusOk()
			.bodyJson()
			.extractingPath("$[*].name")
			.isEqualTo(List.of("한글날"));
		assertThat(mvc.post()
			.uri("/api/holidays")
			.header("Authorization", session)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"date\": \"2026-10-03\", \"name\": \"개천절\"}")).hasStatus(HttpStatus.FORBIDDEN);
		assertThat(mvc.delete().uri("/api/holidays/{id}", hangul).header("Authorization", session))
			.hasStatus(HttpStatus.FORBIDDEN);
		assertThat(mvc.get().uri("/api/holidays?year=2026")).bodyJson().extractingPath("$.length()").isEqualTo(1);
	}

	@Test
	void recordsWhoChangedHolidaysInTheChangeLog() {
		long hangul = id(create("2026-10-09", "한글날"));
		mvc.delete().uri("/api/holidays/{id}", hangul).exchange();

		var logs = assertThat(mvc.get().uri("/api/change-logs?targetType=HOLIDAY&targetId={id}", hangul))
			.hasStatusOk()
			.bodyJson();
		logs.extractingPath("$.content[*].action").isEqualTo(List.of("DELETED", "CREATED"));
		logs.extractingPath("$.content[0].targetName").isEqualTo("2026-10-09 한글날");
		logs.extractingPath("$.content[0].actor.name").asString().startsWith("tester-");
	}

	private MvcTestResult create(String date, String name) {
		return post("{\"date\": \"%s\", \"name\": \"%s\"}".formatted(date, name));
	}

	private MvcTestResult post(String body) {
		return mvc.post().uri("/api/holidays").contentType(MediaType.APPLICATION_JSON).content(body).exchange();
	}

	private static long id(MvcTestResult result) {
		try {
			return ((Number) JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.id"))
				.longValue();
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
