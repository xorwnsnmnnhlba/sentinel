package com.sentinel.application;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.sentinel.TestcontainersConfiguration;
import com.sentinel.domain.Environment;
import com.sentinel.domain.TestResult;
import com.sentinel.domain.TestRun;
import com.sentinel.domain.TestSuite;
import com.sentinel.infra.persistence.TestRunRepository;
import com.sentinel.runner.HttpExchange;
import com.sentinel.runner.HttpMethod;
import com.sentinel.runner.RequestExecutionException;
import com.sentinel.runner.extract.Extractor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/** 응답을 받지 못한 케이스의 재시도와 결과 기록 (재시도 1회, 대기 없음) */
@SpringBootTest(properties = "sentinel.runner.retry.delay=0s")
@Import({ TestcontainersConfiguration.class, FakeRequestExecutorConfiguration.class, TestFixtures.class })
class RunRetryTest {

	@Autowired
	private TestRunService service;

	@Autowired
	private FakeRequestExecutor executor;

	@Autowired
	private TestRunRepository runs;

	@Autowired
	private TestFixtures fixtures;

	private TestSuite suite;

	private Environment environment;

	@BeforeEach
	void setUp() {
		suite = fixtures.suite(fixtures.project());
		environment = fixtures.environment(suite.getProject(), Map.of());
	}

	@AfterEach
	void cleanUp() {
		fixtures.deleteAll();
		executor.reset();
	}

	@Test
	void recordsTheResponseReceivedAfterSendingAgain() {
		fixtures.testCase(suite, HttpMethod.POST, "/orders", null, "{}");
		AtomicInteger calls = new AtomicInteger();
		// 첫 전송은 연결 실패 (서버에 닿지 않아 등록 요청도 다시 보냄)
		executor.respondWith(request -> {
			if (calls.incrementAndGet() == 1) {
				throw new RequestExecutionException(RequestExecutionException.Reason.CONNECTION_FAILED, request,
						Duration.ofSeconds(5), null);
			}
			return new HttpExchange(request, 201, Map.of(), "{}", Duration.ofMillis(7));
		});

		TestRunDetail.Result result = runOnlyResult();

		assertThat(result.verdict()).isEqualTo(TestResult.Verdict.PASSED);
		assertThat(result.retries()).isEqualTo(1);
		assertThat(result.elapsedMs()).isEqualTo(7);
		// 실행 행에도 재시도한 결과 수 (불안정 케이스 조회용)
		assertThat(runs.findAll()).singleElement().extracting(TestRun::getRetriedCount).isEqualTo(1);
		assertThat(executor.requests()).hasSize(2);
	}

	@Test
	void keepsTheRetryCountOfCasesThatExtractValues() {
		fixtures.testCase(suite, HttpMethod.GET, "/login", null, null, List.of(),
				List.of(new Extractor("token", Extractor.Source.BODY, "$.token")));
		AtomicInteger calls = new AtomicInteger();
		executor.respondWith(request -> {
			if (calls.incrementAndGet() == 1) {
				throw new RequestExecutionException(RequestExecutionException.Reason.TIMEOUT, request,
						Duration.ofSeconds(30), null);
			}
			return new HttpExchange(request, 200, Map.of(), "{\"token\": \"t-1\"}", Duration.ofMillis(7));
		});

		TestRunDetail.Result result = runOnlyResult();

		assertThat(result.verdict()).isEqualTo(TestResult.Verdict.PASSED);
		assertThat(result.retries()).isEqualTo(1);
	}

	@Test
	void recordsTheLastErrorWhenThereIsStillNoResponse() {
		fixtures.testCase(suite, HttpMethod.GET, "/slow", null, null);
		executor.fail(RequestExecutionException.Reason.TIMEOUT);

		TestRunDetail.Result result = runOnlyResult();

		assertThat(result.verdict()).isEqualTo(TestResult.Verdict.ERROR);
		assertThat(result.errorReason()).isEqualTo(TestResult.ErrorReason.TIMEOUT);
		assertThat(result.retries()).isEqualTo(1);
		assertThat(executor.requests()).hasSize(2);
	}

	@Test
	void doesNotSendAgainWhatTheServerMayHaveProcessed() {
		fixtures.testCase(suite, HttpMethod.POST, "/orders", null, "{}");
		executor.fail(RequestExecutionException.Reason.TIMEOUT);

		TestRunDetail.Result result = runOnlyResult();

		assertThat(result.errorReason()).isEqualTo(TestResult.ErrorReason.TIMEOUT);
		assertThat(result.retries()).isZero();
		assertThat(executor.requests()).hasSize(1);
	}

	@Test
	void recordsNoRetriesForCasesThatResponded() {
		fixtures.testCase(suite, HttpMethod.GET, "/ok", null, null);

		assertThat(runOnlyResult().retries()).isZero();
		assertThat(executor.requests()).hasSize(1);
		assertThat(runs.findAll()).singleElement().extracting(TestRun::getRetriedCount).isEqualTo(0);
	}

	private TestRunDetail.Result runOnlyResult() {
		long runId = service.runSuite(suite.getId(), environment.getId(), TestRun.Trigger.SCHEDULED, null);
		List<TestRunDetail.Result> results = service.getRun(runId).results();
		assertThat(results).hasSize(1);
		return results.getFirst();
	}

}
