package com.sentinel.api;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.jayway.jsonpath.JsonPath;
import com.sentinel.TestAuthConfiguration;
import com.sentinel.TestcontainersConfiguration;
import com.sentinel.application.FakeRequestExecutorConfiguration;
import com.sentinel.application.MutableClock;
import com.sentinel.application.TestFixtures;
import com.sentinel.domain.Environment;
import com.sentinel.domain.Project;
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

/** 환경 점검 시간 등록·조회·변경·삭제 ({@code /api/maintenance-windows}) */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, FakeRequestExecutorConfiguration.class, TestFixtures.class,
		TestAuthConfiguration.class, MaintenanceWindowApiTest.ClockConfiguration.class })
class MaintenanceWindowApiTest {

	private static final Instant NOW = Instant.parse("2026-09-29T01:00:00Z");

	@Autowired
	private MockMvcTester mvc;

	@Autowired
	private TestFixtures fixtures;

	@Autowired
	private MutableClock clock;

	private Project project;

	private Environment dev;

	@BeforeEach
	void setUp() {
		clock.set(NOW);
		project = fixtures.project();
		dev = fixtures.environment(project, "dev", Map.of());
	}

	@AfterEach
	void cleanUp() {
		fixtures.deleteAll();
	}

	@Test
	void createsAndListsCurrentAndUpcomingWindows() {
		Environment staging = fixtures.environment(project, "staging", Map.of());
		MvcTestResult created = create(dev.getId(), NOW.minusSeconds(60), NOW.plus(Duration.ofMinutes(30)), " GW 배포 ");
		create(staging.getId(), NOW.plus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(2)), null);

		assertThat(created).hasStatus(HttpStatus.CREATED);
		assertThat(created.getResponse().getHeader("Location")).isEqualTo("/api/maintenance-windows/" + id(created));
		assertThat(mvc.get().uri(created.getResponse().getHeader("Location"))).hasStatusOk()
			.bodyJson()
			.extractingPath("$.reason")
			.isEqualTo("GW 배포");
		var body = assertThat(created).bodyJson();
		body.extractingPath("$.environment.name").isEqualTo("dev");
		body.extractingPath("$.project.id").isEqualTo(project.getId().intValue());
		body.extractingPath("$.project.name").isEqualTo(project.getName());
		body.extractingPath("$.reason").isEqualTo("GW 배포");
		body.extractingPath("$.active").isEqualTo(true);
		body.extractingPath("$.startsAt").isEqualTo("2026-09-29T00:59:00Z");
		body.extractingPath("$.endsAt").isEqualTo("2026-09-29T01:30:00Z");
		body.extractingPath("$.createdBy.name").asString().startsWith("tester-");

		// 시작 순. 예정된 점검은 active false
		var all = assertThat(mvc.get().uri("/api/maintenance-windows")).hasStatusOk().bodyJson();
		all.extractingPath("$[*].environment.name").isEqualTo(List.of("dev", "staging"));
		all.extractingPath("$[1].active").isEqualTo(false);
		all.extractingPath("$[1].reason").isNull();
		assertThat(mvc.get().uri("/api/maintenance-windows?environmentId={id}", staging.getId())).bodyJson()
			.extractingPath("$[*].environment.name")
			.isEqualTo(List.of("staging"));

		// 끝난 점검은 목록에서 빠지지만 단건으로는 조회
		clock.set(NOW.plus(Duration.ofMinutes(30)));
		assertThat(mvc.get().uri("/api/maintenance-windows")).bodyJson()
			.extractingPath("$[*].environment.name")
			.isEqualTo(List.of("staging"));
		assertThat(mvc.get().uri("/api/maintenance-windows/{id}", id(created))).hasStatusOk()
			.bodyJson()
			.extractingPath("$.active")
			.isEqualTo(false);
	}

	@Test
	void extendsOrShortensAndClearsTheReason() {
		long windowId = id(create(dev.getId(), NOW, NOW.plus(Duration.ofMinutes(10)), "GW 배포"));

		// 생략한 사유는 유지
		assertThat(patch(windowId, "{\"endsAt\": \"2026-09-29T01:30:00Z\"}")).hasStatusOk()
			.bodyJson()
			.extractingPath("$.reason")
			.isEqualTo("GW 배포");
		var extended = assertThat(patch(windowId, "{\"endsAt\": \"2026-09-29T02:00:00Z\", \"reason\": \"\"}"))
			.hasStatusOk()
			.bodyJson();
		extended.extractingPath("$.endsAt").isEqualTo("2026-09-29T02:00:00Z");
		extended.extractingPath("$.startsAt").isEqualTo("2026-09-29T01:00:00Z");
		extended.extractingPath("$.reason").isNull();

		// 이미 끝난 점검은 바꿀 수 없음
		long ended = id(create(dev.getId(), NOW, NOW.plus(Duration.ofMinutes(5)), null));
		clock.set(NOW.plus(Duration.ofMinutes(5)));
		assertThat(patch(ended, "{\"endsAt\": \"2026-09-29T03:00:00Z\"}")).hasStatus(HttpStatus.CONFLICT)
			.bodyJson()
			.extractingPath("$.detail")
			.isEqualTo("maintenance window " + ended + " has already ended");
		assertThat(patch(windowId, "{\"endsAt\": \"2026-09-29T00:30:00Z\"}")).hasStatus(HttpStatus.BAD_REQUEST);
	}

	@Test
	void deletingCancelsOrEndsTheWindow() {
		long windowId = id(create(dev.getId(), NOW, NOW.plus(Duration.ofMinutes(10)), null));

		assertThat(mvc.delete().uri("/api/maintenance-windows/{id}", windowId)).hasStatus(HttpStatus.NO_CONTENT);

		assertThat(mvc.get().uri("/api/maintenance-windows")).bodyJson().extractingPath("$.length()").isEqualTo(0);
		assertThat(mvc.get().uri("/api/maintenance-windows/{id}", windowId)).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(mvc.delete().uri("/api/maintenance-windows/{id}", windowId)).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(patch(windowId, "{\"reason\": \"x\"}")).hasStatus(HttpStatus.NOT_FOUND)
			.bodyJson()
			.extractingPath("$.detail")
			.isEqualTo("maintenance window " + windowId + " not found");
	}

	@Test
	void leavesOutWindowsOfDeletedEnvironments() {
		long windowId = id(create(dev.getId(), NOW, NOW.plus(Duration.ofMinutes(10)), null));

		assertThat(mvc.delete().uri("/api/environments/{id}", dev.getId())).hasStatus(HttpStatus.NO_CONTENT);

		assertThat(mvc.get().uri("/api/maintenance-windows")).bodyJson().extractingPath("$.length()").isEqualTo(0);
		assertThat(mvc.get().uri("/api/maintenance-windows/{id}", windowId)).hasStatus(HttpStatus.NOT_FOUND);
	}

	@Test
	void recordsWhoChangedTheWindowInTheChangeLog() {
		long windowId = id(create(dev.getId(), NOW, NOW.plus(Duration.ofMinutes(10)), null));
		patch(windowId, "{\"endsAt\": \"2026-09-29T01:20:00Z\", \"reason\": \"연장\"}");
		mvc.delete().uri("/api/maintenance-windows/{id}", windowId).exchange();

		var logs = assertThat(mvc.get().uri("/api/change-logs?targetType=MAINTENANCE_WINDOW&targetId={id}", windowId))
			.hasStatusOk()
			.bodyJson();
		logs.extractingPath("$.content[*].action").isEqualTo(List.of("DELETED", "UPDATED", "CREATED"));
		logs.extractingPath("$.content[1].fields").isEqualTo(List.of("endsAt", "reason"));
		logs.extractingPath("$.content[0].target.name").isEqualTo("dev");
		logs.extractingPath("$.content[0].project.id").isEqualTo(project.getId().intValue());
	}

	@Test
	void rejectsInvalidWindows() {
		assertThat(create(-1, NOW, NOW.plusSeconds(60), null)).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(create(dev.getId(), NOW, NOW, null)).hasStatus(HttpStatus.BAD_REQUEST)
			.bodyJson()
			.extractingPath("$.detail")
			.isEqualTo("endsAt must be after startsAt");
		assertThat(create(dev.getId(), NOW.minusSeconds(120), NOW.minusSeconds(60), null))
			.hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(create(dev.getId(), NOW, NOW.plus(Duration.ofDays(8)), null)).hasStatus(HttpStatus.BAD_REQUEST)
			.bodyJson()
			.extractingPath("$.detail")
			.isEqualTo("a maintenance window can last at most 7 days");
		assertThat(create(dev.getId(), NOW, NOW.plusSeconds(60), "a".repeat(201))).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(post("{\"environmentId\": %d, \"startsAt\": \"2026-09-29T01:00:00Z\"}".formatted(dev.getId())))
			.hasStatus(HttpStatus.BAD_REQUEST);
	}

	private MvcTestResult create(long environmentId, Instant startsAt, Instant endsAt, String reason) {
		return post("{\"environmentId\": %d, \"startsAt\": \"%s\", \"endsAt\": \"%s\", \"reason\": %s}".formatted(
				environmentId, startsAt, endsAt, reason == null ? "null" : "\"" + reason + "\""));
	}

	private MvcTestResult post(String body) {
		return mvc.post()
			.uri("/api/maintenance-windows")
			.contentType(MediaType.APPLICATION_JSON)
			.content(body)
			.exchange();
	}

	private MvcTestResult patch(long windowId, String body) {
		return mvc.patch()
			.uri("/api/maintenance-windows/{id}", windowId)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body)
			.exchange();
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
