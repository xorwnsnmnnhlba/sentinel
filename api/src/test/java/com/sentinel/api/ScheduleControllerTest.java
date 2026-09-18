package com.sentinel.api;

import java.util.Map;

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
@Import({ TestcontainersConfiguration.class, FakeRequestExecutorConfiguration.class, TestFixtures.class })
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
	void createsDisablesAndEnablesSchedule() {
		Project project = fixtures.project();
		Environment environment = fixtures.environment(project, Map.of());
		TestSuite suite = fixtures.suite(project);

		MvcTestResult created = mvc.post()
			.uri("/api/test-schedules")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"suiteId": %d, "environmentId": %d, "cron": "10 9 * * 1-5"}
					""".formatted(suite.getId(), environment.getId()))
			.exchange();

		assertThat(created).hasStatus(HttpStatus.CREATED);
		assertThat(created).bodyJson().extractingPath("$.cron").isEqualTo("10 9 * * 1-5");
		assertThat(created).bodyJson().extractingPath("$.zone").isEqualTo("Asia/Seoul");
		assertThat(created).bodyJson().extractingPath("$.enabled").isEqualTo(true);
		assertThat(created).bodyJson().extractingPath("$.nextRunAt").isNotNull();

		String location = created.getResponse().getHeader("Location");
		assertThat(mvc.post().uri(location + "/disable")).hasStatusOk()
			.bodyJson()
			.extractingPath("$.nextRunAt")
			.isNull();
		assertThat(mvc.post().uri(location + "/enable")).hasStatusOk()
			.bodyJson()
			.extractingPath("$.enabled")
			.isEqualTo(true);
	}

	@Test
	void rejectsInvalidCronWithProblemDetail() {
		Project project = fixtures.project();
		Environment environment = fixtures.environment(project, Map.of());
		TestSuite suite = fixtures.suite(project);

		assertThat(mvc.post()
			.uri("/api/test-schedules")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"suiteId": %d, "environmentId": %d, "cron": "0 10 9 * * *"}
					""".formatted(suite.getId(), environment.getId())))
			.hasStatus(HttpStatus.BAD_REQUEST)
			.bodyJson()
			.extractingPath("$.detail")
			.asString()
			.contains("5 fields");
	}

}
