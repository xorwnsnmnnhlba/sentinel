package com.sentinel.application;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.sentinel.domain.BaseEntity;
import com.sentinel.domain.Environment;
import com.sentinel.domain.RequestSpec;
import com.sentinel.domain.TestCase;
import com.sentinel.domain.TestResult;
import com.sentinel.domain.TestResult.ErrorReason;
import com.sentinel.domain.TestRun;
import com.sentinel.domain.TestSuite;
import com.sentinel.infra.persistence.EnvironmentRepository;
import com.sentinel.infra.persistence.TestCaseRepository;
import com.sentinel.infra.persistence.TestResultRepository;
import com.sentinel.infra.persistence.TestRunRepository;
import com.sentinel.infra.persistence.TestSuiteRepository;
import com.sentinel.runner.HttpExchange;
import com.sentinel.runner.RequestExecutionException;
import com.sentinel.runner.RequestExecutor;
import com.sentinel.runner.RequestTemplate;
import com.sentinel.runner.ResolvedRequest;
import com.sentinel.runner.UnresolvedVariableException;
import com.sentinel.runner.assertion.Assertion;
import com.sentinel.runner.assertion.AssertionEvaluator;
import com.sentinel.runner.assertion.AssertionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 실행 흐름: 준비(트랜잭션) → 케이스별 전송(트랜잭션 밖) + 결과 기록(케이스별 트랜잭션) → 완료 처리(트랜잭션).
 * <p>
 * HTTP 호출 동안 DB 커넥션을 점유하지 않기 위해 전송 구간을 트랜잭션에서 분리. 커넥션 풀 크기 5 전제.
 * 스위트 내 케이스는 ID 순서로 순차 실행하여 GW 동시 호출 없음.
 */
@Service
public class TestRunService {

	private static final Logger log = LoggerFactory.getLogger(TestRunService.class);

	private final TestSuiteRepository suites;

	private final TestCaseRepository testCases;

	private final EnvironmentRepository environments;

	private final TestRunRepository runs;

	private final TestResultRepository results;

	private final RequestExecutor executor;

	private final TransactionTemplate transaction;

	private final TransactionTemplate readOnlyTransaction;

	public TestRunService(TestSuiteRepository suites, TestCaseRepository testCases, EnvironmentRepository environments,
			TestRunRepository runs, TestResultRepository results, RequestExecutor executor,
			PlatformTransactionManager transactionManager) {
		this.suites = suites;
		this.testCases = testCases;
		this.environments = environments;
		this.runs = runs;
		this.results = results;
		this.executor = executor;
		this.transaction = new TransactionTemplate(transactionManager);
		this.readOnlyTransaction = new TransactionTemplate(transactionManager);
		this.readOnlyTransaction.setReadOnly(true);
	}

	/**
	 * 케이스 1건 수동 실행. 응답 미수신·변수 누락도 실패 결과로 기록하며 예외로 전파하지 않음.
	 * @return 생성된 실행 ID
	 * @throws NotFoundException 케이스 또는 환경 미존재
	 * @throws InvalidRequestException 케이스와 환경의 프로젝트 불일치
	 */
	public long runTestCase(long testCaseId, long environmentId) {
		Prepared prepared = transaction.execute(status -> {
			TestCase testCase = testCases.findById(testCaseId)
				.orElseThrow(() -> new NotFoundException("test case", testCaseId));
			return start(testCase.getSuite(), findEnvironment(environmentId), List.of(testCase),
					TestRun.Trigger.MANUAL);
		});
		return executeAndRecord(prepared);
	}

	/**
	 * 스위트 전체 실행. 케이스가 없는 스위트도 결과 0건의 완료된 실행으로 기록.
	 * @return 생성된 실행 ID
	 * @throws NotFoundException 스위트 또는 환경 미존재
	 * @throws InvalidRequestException 스위트와 환경의 프로젝트 불일치
	 */
	public long runSuite(long suiteId, long environmentId, TestRun.Trigger trigger) {
		Prepared prepared = transaction.execute(status -> {
			TestSuite suite = suites.findById(suiteId).orElseThrow(() -> new NotFoundException("test suite", suiteId));
			return start(suite, findEnvironment(environmentId), testCases.findBySuiteIdOrderById(suiteId), trigger);
		});
		return executeAndRecord(prepared);
	}

	public TestRunDetail getRun(long runId) {
		return readOnlyTransaction.execute(status -> {
			TestRun run = runs.findById(runId).orElseThrow(() -> new NotFoundException("test run", runId));
			return TestRunDetail.of(run, results.findWithTestCaseByRunId(runId));
		});
	}

	private Environment findEnvironment(long environmentId) {
		return environments.findById(environmentId)
			.orElseThrow(() -> new NotFoundException("environment", environmentId));
	}

	private Prepared start(TestSuite suite, Environment environment, List<TestCase> cases, TestRun.Trigger trigger) {
		if (!BaseEntity.same(suite.getProject(), environment.getProject())) {
			throw new InvalidRequestException("environment " + environment.getId()
					+ " does not belong to the project of test suite " + suite.getId());
		}
		TestRun run = runs.save(TestRun.start(suite, environment, trigger, Instant.now()));
		List<PreparedCase> preparedCases = cases.stream()
			.map(testCase -> new PreparedCase(testCase.getId(), templateOf(testCase.getRequest(), environment),
					testCase.getAssertions()))
			.toList();
		return new Prepared(run.getId(), suite.getId(), trigger, preparedCases, Map.copyOf(environment.getVariables()));
	}

	private static RequestTemplate templateOf(RequestSpec spec, Environment environment) {
		return new RequestTemplate(spec.getMethod(), environment.getBaseUrl() + spec.getPath(), spec.getHeaders(),
				spec.getBody());
	}

	private long executeAndRecord(Prepared prepared) {
		long startedAt = System.nanoTime();
		Map<TestResult.Verdict, Integer> counts = new EnumMap<>(TestResult.Verdict.class);
		for (PreparedCase preparedCase : prepared.cases()) {
			Execution execution = execute(preparedCase, prepared.variables());
			TestResult.Verdict verdict = transaction
				.execute(status -> results.save(resultOf(prepared.runId(), preparedCase, execution)).getVerdict());
			counts.merge(verdict, 1, Integer::sum);
		}
		transaction.executeWithoutResult(status -> runs.findById(prepared.runId()).orElseThrow().complete(Instant.now()));
		log.info("Run {} completed: trigger={} suite={} total={} passed={} failed={} errors={} elapsed={}ms",
				prepared.runId(), prepared.trigger(), prepared.suiteId(), prepared.cases().size(),
				counts.getOrDefault(TestResult.Verdict.PASSED, 0), counts.getOrDefault(TestResult.Verdict.FAILED, 0),
				counts.getOrDefault(TestResult.Verdict.ERROR, 0), Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
		return prepared.runId();
	}

	private Execution execute(PreparedCase preparedCase, Map<String, String> variables) {
		ResolvedRequest request;
		try {
			request = preparedCase.template().resolve(variables);
		}
		catch (UnresolvedVariableException ex) {
			return new Failed(ErrorReason.UNRESOLVED_VARIABLE, ex.getMessage(), null, null);
		}
		catch (IllegalArgumentException ex) {
			return new Failed(ErrorReason.INVALID_REQUEST, ex.getMessage(), null, null);
		}

		long startedAt = System.nanoTime();
		try {
			HttpExchange exchange = executor.execute(request);
			return new Responded(exchange, AssertionEvaluator.evaluate(preparedCase.assertions(), exchange));
		}
		catch (RequestExecutionException ex) {
			return new Failed(ErrorReason.valueOf(ex.reason().name()), messageOf(ex), request, ex.elapsed());
		}
		catch (IllegalArgumentException ex) {
			// JDK HttpClient 제한 헤더(Host, Content-Length 등) 지정 시
			return new Failed(ErrorReason.INVALID_REQUEST, ex.getMessage(), request, null);
		}
		catch (RuntimeException ex) {
			return new Failed(ErrorReason.UNEXPECTED, messageOf(ex), request,
					Duration.ofNanos(System.nanoTime() - startedAt));
		}
	}

	private TestResult resultOf(long runId, PreparedCase preparedCase, Execution execution) {
		TestRun run = runs.getReferenceById(runId);
		TestCase testCase = testCases.getReferenceById(preparedCase.testCaseId());
		return switch (execution) {
			case Responded responded -> TestResult.responded(run, testCase, responded.exchange(),
					responded.assertionResults());
			case Failed failed -> TestResult.failed(run, testCase, preparedCase.template().method(), failed.reason(),
					failed.message(), failed.request(), failed.elapsed());
		};
	}

	private static String messageOf(Throwable ex) {
		Throwable root = ex;
		while (root.getCause() != null) {
			root = root.getCause();
		}
		return root == ex || root.getMessage() == null ? ex.getMessage() : ex.getMessage() + " (" + root + ")";
	}

	private record Prepared(long runId, long suiteId, TestRun.Trigger trigger, List<PreparedCase> cases,
			Map<String, String> variables) {
	}

	private record PreparedCase(long testCaseId, RequestTemplate template, List<Assertion> assertions) {
	}

	private sealed interface Execution permits Responded, Failed {
	}

	private record Responded(HttpExchange exchange, List<AssertionResult> assertionResults) implements Execution {
	}

	private record Failed(ErrorReason reason, String message, ResolvedRequest request, Duration elapsed)
			implements Execution {
	}

}
