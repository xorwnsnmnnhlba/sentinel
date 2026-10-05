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

@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, FakeRequestExecutorConfiguration.class, TestFixtures.class,
		TestAuthConfiguration.class })
class ScheduleControllerTest {

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
	void createsListsAndPatchesSchedule() {
		Project project = fixtures.project();
		Environment environment = fixtures.environment(project, Map.of());
		TestSuite suite = fixtures.suite(project);
		TestSuite other = fixtures.suite(project);

		MvcTestResult created = create(suite, environment, "10 9 * * 1-5");
		create(other, environment, "30 12 * * 1-5");

		assertThat(created).hasStatus(HttpStatus.CREATED);
		assertThat(created).bodyJson().extractingPath("$.zone").isEqualTo("Asia/Seoul");
		assertThat(created).bodyJson().extractingPath("$.nextRunAt").isNotNull();
		String location = created.getResponse().getHeader("Location");
		assertThat(location).startsWith("/api/schedules/");

		assertThat(mvc.get().uri("/api/schedules?suiteId={id}", suite.getId())).hasStatusOk()
			.bodyJson()
			.extractingPath("$[*].cron")
			.asArray()
			.containsExactly("10 9 * * 1-5");
		assertThat(mvc.get().uri("/api/schedules")).bodyJson().extractingPath("$.length()").isEqualTo(2);

		assertThat(patch(location, "{\"enabled\": false}")).hasStatusOk()
			.bodyJson()
			.extractingPath("$.nextRunAt")
			.isNull();
		assertThat(patch(location, "{}")).hasStatusOk().bodyJson().extractingPath("$.enabled").isEqualTo(false);
		assertThat(patch(location, "{\"enabled\": true}")).hasStatusOk()
			.bodyJson()
			.extractingPath("$.nextRunAt")
			.isNotNull();
		assertThat(mvc.get().uri(location)).hasStatusOk().bodyJson().extractingPath("$.enabled").isEqualTo(true);
	}

	@Test
	void togglesHolidaySkipping() {
		Project project = fixtures.project();
		Environment environment = fixtures.environment(project, Map.of());
		TestSuite suite = fixtures.suite(project);
		// 생략 시 꺼짐
		assertThat(create(suite, environment, "10 9 * * 1-5")).bodyJson()
			.extractingPath("$.skipHolidays")
			.isEqualTo(false);
		MvcTestResult created = mvc.post()
			.uri("/api/schedules")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"suiteId\": %d, \"environmentId\": %d, \"cron\": \"0 9 * * *\", \"skipHolidays\": true}"
				.formatted(suite.getId(), environment.getId()))
			.exchange();
		assertThat(created).hasStatus(HttpStatus.CREATED).bodyJson().extractingPath("$.skipHolidays").isEqualTo(true);
		String location = created.getResponse().getHeader("Location");

		// 생략하면 유지
		assertThat(patch(location, "{\"cron\": \"30 9 * * *\"}")).bodyJson()
			.extractingPath("$.skipHolidays")
			.isEqualTo(true);
		assertThat(patch(location, "{\"skipHolidays\": false}")).hasStatusOk()
			.bodyJson()
			.extractingPath("$.skipHolidays")
			.isEqualTo(false);
		assertThat(mvc.get().uri(location)).bodyJson().extractingPath("$.skipHolidays").isEqualTo(false);
		assertThat(patch(location, "{\"skipHolidays\": true}")).bodyJson()
			.extractingPath("$.skipHolidays")
			.isEqualTo(true);
	}

	@Test
	void changesEnvironmentOfSchedule() {
		Project project = fixtures.project();
		Environment dev = fixtures.environment(project, Map.of());
		Environment staging = fixtures.environment(project, "staging", Map.of());
		TestSuite suite = fixtures.suite(project);
		String location = create(suite, dev, "10 9 * * 1-5").getResponse().getHeader("Location");

		assertThat(patch(location, "{\"environmentId\": %d}".formatted(staging.getId()))).hasStatusOk()
			.bodyJson()
			.extractingPath("$.environmentId")
			.isEqualTo(staging.getId().intValue());
		assertThat(mvc.get().uri(location)).bodyJson().extractingPath("$.cron").isEqualTo("10 9 * * 1-5");
	}

	@Test
	void rejectsEnvironmentOfAnotherProjectOrMissingEnvironment() {
		Project project = fixtures.project();
		Environment environment = fixtures.environment(project, Map.of());
		Environment otherProjectEnvironment = fixtures.environment(fixtures.project(), Map.of());
		String location = create(fixtures.suite(project), environment, "10 9 * * 1-5").getResponse()
			.getHeader("Location");

		assertThat(patch(location, "{\"environmentId\": %d}".formatted(otherProjectEnvironment.getId())))
			.hasStatus(HttpStatus.BAD_REQUEST)
			.bodyJson()
			.extractingPath("$.detail")
			.asString()
			.contains("same project");
		assertThat(patch(location, "{\"environmentId\": -1}")).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(mvc.get().uri(location)).bodyJson()
			.extractingPath("$.environmentId")
			.isEqualTo(environment.getId().intValue());
	}

	@Test
	void rejectsInvalidCronWithProblemDetail() {
		Project project = fixtures.project();
		Environment environment = fixtures.environment(project, Map.of());

		assertThat(create(fixtures.suite(project), environment, "0 10 9 * * *")).hasStatus(HttpStatus.BAD_REQUEST)
			.bodyJson()
			.extractingPath("$.detail")
			.asString()
			.contains("5 fields");
	}

	@Test
	void returnsNotFoundForMissingSchedule() {
		assertThat(mvc.get().uri("/api/schedules/{id}", -1)).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(patch("/api/schedules/-1", "{\"enabled\": true}")).hasStatus(HttpStatus.NOT_FOUND);
	}

	private MvcTestResult create(TestSuite suite, Environment environment, String cron) {
		return mvc.post()
			.uri("/api/schedules")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"suiteId\": %d, \"environmentId\": %d, \"cron\": \"%s\"}".formatted(suite.getId(),
					environment.getId(), cron))
			.exchange();
	}

	private MvcTestResult patch(String uri, String body) {
		return mvc.patch().uri(uri).contentType(MediaType.APPLICATION_JSON).content(body).exchange();
	}

}
