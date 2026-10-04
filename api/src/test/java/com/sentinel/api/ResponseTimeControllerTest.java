package com.sentinel.api;

import java.util.List;
import java.util.Map;

import com.sentinel.TestAuthConfiguration;
import com.sentinel.TestcontainersConfiguration;
import com.sentinel.application.FakeRequestExecutor;
import com.sentinel.application.FakeRequestExecutorConfiguration;
import com.sentinel.application.TestFixtures;
import com.sentinel.domain.Environment;
import com.sentinel.domain.Project;
import com.sentinel.domain.TestCase;
import com.sentinel.domain.TestRun;
import com.sentinel.domain.TestSuite;
import com.sentinel.runner.HttpMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 응답시간 API의 요청·응답 형태. 집계 값은 {@code ResponseTimeServiceTest}에서 검증
 */
@SpringBootTest(properties = "sentinel.schedule.enabled=false")
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, FakeRequestExecutorConfiguration.class, TestFixtures.class,
		TestAuthConfiguration.class })
class ResponseTimeControllerTest {

	@Autowired
	private MockMvcTester mvc;

	@Autowired
	private TestFixtures fixtures;

	@Autowired
	private FakeRequestExecutor executor;

	private TestSuite suite;

	private Environment environment;

	private TestCase testCase;

	@BeforeEach
	void setUp() {
		Project project = fixtures.project();
		environment = fixtures.environment(project, Map.of());
		suite = fixtures.suite(project);
		testCase = fixtures.testCase(suite, HttpMethod.GET, "/health", null, null);
	}

	@AfterEach
	void cleanUp() {
		fixtures.deleteAll();
		executor.reset();
	}

	@Test
	void returnsDailyPointsForACase() {
		fixtures.runSuite(suite.getId(), environment.getId(), TestRun.Trigger.SCHEDULED, null);

		var body = assertThat(mvc.get()
			.uri("/api/response-time-trends?caseId={c}&environmentId={e}", testCase.getId(), environment.getId()))
			.hasStatusOk()
			.bodyJson();

		body.extractingPath("$.caseId").isEqualTo(testCase.getId().intValue());
		body.extractingPath("$.points.length()").isEqualTo(30);
		// 오늘(마지막 날)에 방금 실행한 결과 1건. 가짜 실행기의 소요 시간은 12ms
		body.extractingPath("$.points[29].samples").isEqualTo(1);
		body.extractingPath("$.points[29].p95").isEqualTo(12);
		body.extractingPath("$.points[0].p50").isNull();
		body.extractingPath("$.points[0].date").asString().matches("\\d{4}-\\d{2}-\\d{2}");
	}

	@Test
	void returnsEveryCaseOfASuite() {
		var body = assertThat(mvc.get()
			.uri("/api/response-time-summaries?suiteId={s}&environmentId={e}&from=2026-09-01&to=2026-09-07",
					suite.getId(), environment.getId()))
			.hasStatusOk()
			.bodyJson();

		body.extractingPath("$.from").isEqualTo("2026-09-01");
		body.extractingPath("$.to").isEqualTo("2026-09-07");
		body.extractingPath("$.cases[*].caseId").isEqualTo(List.of(testCase.getId().intValue()));
		body.extractingPath("$.cases[0].samples").isEqualTo(0);
	}

	@Test
	void validatesParameters() {
		// 환경은 필수
		assertThat(mvc.get().uri("/api/response-time-trends?caseId={c}", testCase.getId()))
			.hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(mvc.get()
			.uri("/api/response-time-trends?caseId={c}&environmentId={e}&from=09-01", testCase.getId(),
					environment.getId()))
			.hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(mvc.get()
			.uri("/api/response-time-summaries?suiteId={s}&environmentId={e}&from=2026-01-01&to=2026-12-31",
					suite.getId(), environment.getId()))
			.hasStatus(HttpStatus.BAD_REQUEST)
			.bodyJson()
			.extractingPath("$.detail")
			.isEqualTo("period must be at most 90 days");
		assertThat(mvc.get().uri("/api/response-time-trends?caseId=999999&environmentId={e}", environment.getId()))
			.hasStatus(HttpStatus.NOT_FOUND);
	}

}
