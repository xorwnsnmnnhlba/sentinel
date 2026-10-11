package com.sentinel.api;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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
		assertThat(mvc.get().uri(created.getResponse().getHeader("Location"))).hasStatusOk()
			.bodyJson()
			.extractingPath("$.name")
			.isEqualTo("한글날");
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
		assertThat(mvc.get().uri("/api/holidays/{id}", chuseok)).hasStatus(HttpStatus.NOT_FOUND)
			.bodyJson()
			.extractingPath("$.detail")
			.isEqualTo("holiday " + chuseok + " not found");
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
	void importsManyHolidaysAndSkipsDatesAlreadyRegistered() {
		create("2027-01-01", "신정");

		MvcTestResult imported = importHolidays("""
				[{"date": "2026-12-25", "name": " 성탄절 "},
				 {"date": "2027-01-01", "name": "새해"},
				 {"date": "2027-02-07", "name": "설날"}]""");

		assertThat(imported).hasStatus(HttpStatus.CREATED);
		var body = assertThat(imported).bodyJson();
		body.extractingPath("$.created[*].date").isEqualTo(List.of("2026-12-25", "2027-02-07"));
		body.extractingPath("$.created[0].name").isEqualTo("성탄절");
		// 이미 등록된 날짜는 기존 이름 그대로
		body.extractingPath("$.skipped[*].name").isEqualTo(List.of("신정"));
		assertThat(mvc.get().uri("/api/holidays?year=2027")).bodyJson()
			.extractingPath("$[*].name")
			.isEqualTo(List.of("신정", "설날"));

		// 같은 목록을 다시 보내면 모두 건너뜀
		assertThat(importHolidays("[{\"date\": \"2026-12-25\", \"name\": \"성탄절\"}]")).hasStatus(HttpStatus.CREATED)
			.bodyJson()
			.extractingPath("$.created.length()")
			.isEqualTo(0);

		// 등록한 휴일마다 변경 이력
		long christmas = ((Number) JsonPath.read(body(imported), "$.created[0].id")).longValue();
		assertThat(mvc.get().uri("/api/change-logs?targetType=HOLIDAY&targetId={id}", christmas)).bodyJson()
			.extractingPath("$.content[0].target.name")
			.isEqualTo("2026-12-25 성탄절");
	}

	@Test
	void importsNothingWhenAnyHolidayIsInvalid() {
		create("2027-01-01", "신정");

		// 이미 등록된 날짜여도 이름은 확인
		assertThat(importHolidays("""
				[{"date": "2026-12-25", "name": "성탄절"}, {"date": "2027-01-01", "name": " "}]"""))
			.hasStatus(HttpStatus.BAD_REQUEST)
			.bodyJson()
			.extractingPath("$.detail")
			.isEqualTo("holidays[1] 2027-01-01: name must not be blank");
		assertThat(importHolidays("""
				[{"date": "2026-12-25", "name": "성탄절"}, {"date": "2026-12-25", "name": "크리스마스"}]"""))
			.hasStatus(HttpStatus.BAD_REQUEST)
			.bodyJson()
			.extractingPath("$.detail")
			.isEqualTo("holidays[1] 2026-12-25: appears more than once");
		assertThat(importHolidays("[{\"name\": \"성탄절\"}]")).hasStatus(HttpStatus.BAD_REQUEST)
			.bodyJson()
			.extractingPath("$.detail")
			.isEqualTo("holidays[0]: date is required");
		assertThat(importHolidays("[]")).hasStatus(HttpStatus.BAD_REQUEST)
			.bodyJson()
			.extractingPath("$.detail")
			.isEqualTo("holidays must not be empty");
		assertThat(mvc.post().uri("/api/holiday-imports").contentType(MediaType.APPLICATION_JSON).content("{}"))
			.hasStatus(HttpStatus.BAD_REQUEST);

		assertThat(mvc.get().uri("/api/holidays?year=2026")).bodyJson().extractingPath("$.length()").isEqualTo(0);
	}

	@Test
	void limitsTheNumberOfHolidaysPerImport() {
		LocalDate first = LocalDate.parse("2026-01-01");
		assertThat(importHolidays(holidays(first, 101))).hasStatus(HttpStatus.BAD_REQUEST)
			.bodyJson()
			.extractingPath("$.detail")
			.isEqualTo("at most 100 holidays can be imported at once");
		assertThat(importHolidays(holidays(first, 100))).hasStatus(HttpStatus.CREATED)
			.bodyJson()
			.extractingPath("$.created.length()")
			.isEqualTo(100);
	}

	@Test
	void onlyAdministratorsCanCreateOrDelete() {
		long hangul = id(create("2026-10-09", "한글날"));
		String password = UUID.randomUUID().toString();
		User member = users.save(new User("member-" + UUID.randomUUID() + "@sentinel.test", "구성원",
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
		assertThat(mvc.post()
			.uri("/api/holiday-imports")
			.header("Authorization", session)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"holidays\": [{\"date\": \"2026-10-03\", \"name\": \"개천절\"}]}"))
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
		logs.extractingPath("$.content[0].target.name").isEqualTo("2026-10-09 한글날");
		logs.extractingPath("$.content[0].actor.name").asString().startsWith("tester-");
	}

	private MvcTestResult create(String date, String name) {
		return post("{\"date\": \"%s\", \"name\": \"%s\"}".formatted(date, name));
	}

	private MvcTestResult importHolidays(String holidays) {
		return mvc.post()
			.uri("/api/holiday-imports")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"holidays\": " + holidays + "}")
			.exchange();
	}

	/** {@code from}부터 하루씩 {@code count}건 */
	private static String holidays(LocalDate from, int count) {
		return IntStream.range(0, count)
			.mapToObj(i -> "{\"date\": \"%s\", \"name\": \"휴일 %d\"}".formatted(from.plusDays(i), i))
			.collect(Collectors.joining(",", "[", "]"));
	}

	private MvcTestResult post(String body) {
		return mvc.post().uri("/api/holidays").contentType(MediaType.APPLICATION_JSON).content(body).exchange();
	}

	private static long id(MvcTestResult result) {
		return ((Number) JsonPath.read(body(result), "$.id")).longValue();
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
