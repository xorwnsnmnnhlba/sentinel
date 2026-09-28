package com.sentinel.api;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import com.jayway.jsonpath.JsonPath;
import com.sentinel.TestAuthConfiguration;
import com.sentinel.TestcontainersConfiguration;
import com.sentinel.application.FakeRequestExecutor;
import com.sentinel.application.FakeRequestExecutorConfiguration;
import com.sentinel.application.TestFixtures;
import com.sentinel.domain.Environment;
import com.sentinel.domain.Project;
import com.sentinel.domain.TestSuite;
import com.sentinel.runner.HttpExchange;
import com.sentinel.runner.HttpMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 진행 중인 실행 중지. 가짜 실행기가 첫 케이스에서 문이 열릴 때까지 응답하지 않아 실행 도중에 중지 요청
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@Import({ TestcontainersConfiguration.class, FakeRequestExecutorConfiguration.class, TestFixtures.class,
		TestAuthConfiguration.class })
class RunCancelApiTest {

	@Autowired
	private MockMvcTester mvc;

	@Autowired
	private FakeRequestExecutor executor;

	@Autowired
	private TestFixtures fixtures;

	private final CountDownLatch gate = new CountDownLatch(1);

	private TestSuite suite;

	private Environment environment;

	@BeforeEach
	void setUp() {
		Project project = fixtures.project();
		environment = fixtures.environment(project, Map.of());
		suite = fixtures.suite(project);
		for (String path : new String[] { "/first", "/second", "/third" }) {
			fixtures.testCase(suite, HttpMethod.GET, path, null, null);
		}
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
		gate.countDown();
		fixtures.deleteAll();
		executor.reset();
	}

	@Test
	void stopsAfterTheCaseInProgressAndKeepsItsResult(CapturedOutput output) {
		long runId = id(post("/api/runs", "{\"suiteId\": %d, \"environmentId\": %d}".formatted(suite.getId(),
				environment.getId())));

		// 첫 케이스를 보낸 뒤(응답 대기 중) 중지
		waitUntil(() -> !executor.requests().isEmpty());
		MvcTestResult canceled = patch(runId, "{\"status\": \"CANCELED\"}");

		assertThat(canceled).hasStatusOk();
		assertThat(canceled).bodyJson().extractingPath("$.status").isEqualTo("CANCELED");
		assertThat(canceled).bodyJson().extractingPath("$.canceledBy.name").asString().startsWith("tester-");
		assertThat(canceled).bodyJson().extractingPath("$.finishedAt").isNotNull();

		// 진행 중이던 첫 케이스는 끝까지 기록하고 나머지는 보내지 않음
		gate.countDown();
		waitFor(output, "Run " + runId + " canceled");
		assertThat(executor.requests()).extracting(request -> request.uri().getPath()).containsExactly("/first");
		var detail = assertThat(mvc.get().uri("/api/runs/{id}", runId)).bodyJson();
		detail.extractingPath("$.status").isEqualTo("CANCELED");
		detail.extractingPath("$.results.length()").isEqualTo(1);
		detail.extractingPath("$.summary.passed").isEqualTo(1);
		detail.extractingPath("$.plannedCount").isEqualTo(3);
	}

	@Test
	void refusesToCancelFinishedOrUnknownRunsAndOtherStatuses(CapturedOutput output) {
		gate.countDown();
		long runId = id(post("/api/runs", "{\"suiteId\": %d, \"environmentId\": %d}".formatted(suite.getId(),
				environment.getId())));
		waitFor(output, "Run " + runId + " completed");

		assertThat(patch(runId, "{\"status\": \"CANCELED\"}")).hasStatus(HttpStatus.CONFLICT)
			.bodyJson()
			.extractingPath("$.detail")
			.isEqualTo("test run " + runId + " is already COMPLETED");
		assertThat(patch(-1, "{\"status\": \"CANCELED\"}")).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(patch(runId, "{\"status\": \"COMPLETED\"}")).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(patch(runId, "{}")).hasStatus(HttpStatus.BAD_REQUEST);
	}

	private MvcTestResult post(String uri, String body) {
		return mvc.post().uri(uri).contentType(MediaType.APPLICATION_JSON).content(body).exchange();
	}

	private MvcTestResult patch(long runId, String body) {
		return mvc.patch()
			.uri("/api/runs/{id}", runId)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body)
			.exchange();
	}

	/** 백그라운드 실행이 끝났다는 로그가 나올 때까지 대기 */
	private static void waitFor(CapturedOutput output, String line) {
		waitUntil(() -> output.getOut().contains(line));
		assertThat(output.getOut()).contains(line);
	}

	/** 최대 10초 */
	private static void waitUntil(BooleanSupplier condition) {
		for (int attempt = 0; attempt < 200 && !condition.getAsBoolean(); attempt++) {
			try {
				Thread.sleep(50);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(ex);
			}
		}
		assertThat(condition.getAsBoolean()).as("condition within 10s").isTrue();
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

}
