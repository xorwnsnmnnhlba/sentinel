package com.sentinel.api;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.jayway.jsonpath.JsonPath;
import com.sentinel.TestAuthConfiguration;
import com.sentinel.TestcontainersConfiguration;
import com.sentinel.application.FakeRequestExecutor;
import com.sentinel.application.FakeRequestExecutorConfiguration;
import com.sentinel.application.TestFixtures;
import com.sentinel.domain.Environment;
import com.sentinel.domain.Project;
import com.sentinel.domain.TestRun;
import com.sentinel.domain.TestSuite;
import com.sentinel.infra.persistence.TestRunRepository;
import com.sentinel.runner.HttpExchange;
import com.sentinel.runner.HttpMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

/**
 * 수동 실행의 백그라운드 진행. 실행 API는 실행을 만들고 바로 응답하고, 케이스는 백그라운드에서 진행 (동시 실행 한도 1)
 * 가짜 실행기가 문을 열 때까지 응답하지 않아 실행 중 상태를 재현
 */
@SpringBootTest(properties = "sentinel.runner.manual.max-concurrent=1")
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, FakeRequestExecutorConfiguration.class, TestFixtures.class,
		TestAuthConfiguration.class })
class ManualRunApiTest {

	@Autowired
	private MockMvcTester mvc;

	@Autowired
	private FakeRequestExecutor executor;

	@Autowired
	private TestFixtures fixtures;

	@Autowired
	private TestRunRepository runs;

	private final CountDownLatch gate = new CountDownLatch(1);

	private TestSuite suite;

	private Environment environment;

	@BeforeEach
	void setUp() {
		Project project = fixtures.project();
		environment = fixtures.environment(project, Map.of());
		suite = fixtures.suite(project);
		fixtures.testCase(suite, HttpMethod.GET, "/a", null, null);
		fixtures.testCase(suite, HttpMethod.GET, "/b", null, null);
		executor.respondWith(request -> {
			try {
				assertThat(gate.await(10, TimeUnit.SECONDS)).isTrue();
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(ex);
			}
			return new HttpExchange(request, 200, Map.of(), "", Duration.ofMillis(5));
		});
	}

	@AfterEach
	void cleanUp() {
		// 남은 백그라운드 실행을 끝내고 정리
		gate.countDown();
		waitUntilNothingRuns();
		fixtures.deleteAll();
		executor.reset();
	}

	@Test
	void respondsBeforeTheCasesRunAndFinishesInTheBackground() {
		MvcTestResult started = runSuite();

		assertThat(started).hasStatus(HttpStatus.ACCEPTED);
		assertThat(started.getResponse().getHeader("Location")).isEqualTo("/api/runs/" + id(started));
		assertThat(started).bodyJson().extractingPath("$.status").isEqualTo("RUNNING");
		assertThat(started).bodyJson().extractingPath("$.plannedCount").isEqualTo(2);
		assertThat(started).bodyJson().extractingPath("$.results").asArray().isEmpty();

		gate.countDown();
		String finished = waitUntilFinished(id(started));

		assertThat(JsonPath.<String>read(finished, "$.status")).isEqualTo("COMPLETED");
		assertThat(JsonPath.<Integer>read(finished, "$.summary.passed")).isEqualTo(2);
	}

	@Test
	void refusesRunsBeyondTheLimitWithoutCreatingThem() {
		long first = id(runSuite());
		long before = runs.count();

		assertThat(runSuite()).hasStatus(HttpStatus.TOO_MANY_REQUESTS)
			.bodyJson()
			.extractingPath("$.detail")
			.asString()
			.contains("1 manual runs are already in progress");
		assertThat(runs.count()).isEqualTo(before);

		// 앞 실행이 끝나면 자리가 비어 다시 실행 가능
		gate.countDown();
		waitUntilFinished(first);
		assertThat(runSuite()).hasStatus(HttpStatus.ACCEPTED);
	}

	@Test
	void freesTheSlotWhenTheRunCannotBePrepared() {
		assertThat(post("{\"suiteId\": -1, \"environmentId\": %d}".formatted(environment.getId())))
			.hasStatus(HttpStatus.NOT_FOUND);

		gate.countDown();
		assertThat(runSuite()).hasStatus(HttpStatus.ACCEPTED);
	}

	private MvcTestResult runSuite() {
		return post("{\"suiteId\": %d, \"environmentId\": %d}".formatted(suite.getId(), environment.getId()));
	}

	private MvcTestResult post(String body) {
		return mvc.post().uri("/api/runs").contentType(MediaType.APPLICATION_JSON).content(body).exchange();
	}

	/** 실행이 끝날 때까지 상세 조회 (최대 10초) */
	private String waitUntilFinished(long runId) {
		for (int attempt = 0; attempt < 200; attempt++) {
			String detail = body(mvc.get().uri("/api/runs/{id}", runId).exchange());
			if (!"RUNNING".equals(JsonPath.read(detail, "$.status"))) {
				return detail;
			}
			pause();
		}
		throw new AssertionError("run " + runId + " did not finish");
	}

	private void waitUntilNothingRuns() {
		for (int attempt = 0; attempt < 200 && !runs.findByStatus(TestRun.Status.RUNNING).isEmpty(); attempt++) {
			pause();
		}
	}

	private static void pause() {
		try {
			Thread.sleep(50);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
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

}
