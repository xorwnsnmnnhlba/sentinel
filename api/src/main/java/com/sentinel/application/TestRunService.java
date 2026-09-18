package com.sentinel.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

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
import com.sentinel.runner.HttpExchange;
import com.sentinel.runner.RequestExecutionException;
import com.sentinel.runner.RequestExecutor;
import com.sentinel.runner.RequestTemplate;
import com.sentinel.runner.ResolvedRequest;
import com.sentinel.runner.UnresolvedVariableException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 케이스 실행 흐름: 준비(트랜잭션) → 전송(트랜잭션 밖) → 기록(트랜잭션).
 * <p>
 * HTTP 호출 동안 DB 커넥션을 점유하지 않기 위해 전송 구간을 트랜잭션에서 분리. 커넥션 풀 크기 5 전제.
 */
@Service
public class TestRunService {

	private final TestCaseRepository testCases;

	private final EnvironmentRepository environments;

	private final TestRunRepository runs;

	private final TestResultRepository results;

	private final RequestExecutor executor;

	private final TransactionTemplate transaction;

	private final TransactionTemplate readOnlyTransaction;

	public TestRunService(TestCaseRepository testCases, EnvironmentRepository environments, TestRunRepository runs,
			TestResultRepository results, RequestExecutor executor, PlatformTransactionManager transactionManager) {
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
	 * @throws InvalidRunRequestException 케이스와 환경의 프로젝트 불일치
	 */
	public long runTestCase(long testCaseId, long environmentId) {
		Prepared prepared = transaction.execute(status -> prepare(testCaseId, environmentId));
		Execution execution = execute(prepared.template(), prepared.variables());
		transaction.executeWithoutResult(status -> record(prepared, execution));
		return prepared.runId();
	}

	public TestRunDetail getRun(long runId) {
		return readOnlyTransaction.execute(status -> {
			TestRun run = runs.findById(runId).orElseThrow(() -> new NotFoundException("test run", runId));
			return TestRunDetail.of(run, results.findByRunIdOrderById(runId));
		});
	}

	private Prepared prepare(long testCaseId, long environmentId) {
		TestCase testCase = testCases.findById(testCaseId)
			.orElseThrow(() -> new NotFoundException("test case", testCaseId));
		Environment environment = environments.findById(environmentId)
			.orElseThrow(() -> new NotFoundException("environment", environmentId));
		TestSuite suite = testCase.getSuite();
		if (!suite.getProject().getId().equals(environment.getProject().getId())) {
			throw new InvalidRunRequestException("environment " + environmentId
					+ " does not belong to the project of test case " + testCaseId);
		}

		TestRun run = runs.save(TestRun.start(suite, environment, TestRun.Trigger.MANUAL, Instant.now()));
		RequestSpec spec = testCase.getRequest();
		RequestTemplate template = new RequestTemplate(spec.getMethod(), environment.getBaseUrl() + spec.getPath(),
				spec.getHeaders(), spec.getBody());
		return new Prepared(run.getId(), testCaseId, template, Map.copyOf(environment.getVariables()));
	}

	private Execution execute(RequestTemplate template, Map<String, String> variables) {
		ResolvedRequest request;
		try {
			request = template.resolve(variables);
		}
		catch (UnresolvedVariableException ex) {
			return new Failed(ErrorReason.UNRESOLVED_VARIABLE, ex.getMessage(), null, null);
		}
		catch (IllegalArgumentException ex) {
			return new Failed(ErrorReason.INVALID_REQUEST, ex.getMessage(), null, null);
		}

		long startedAt = System.nanoTime();
		try {
			return new Responded(executor.execute(request));
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

	private void record(Prepared prepared, Execution execution) {
		TestRun run = runs.findById(prepared.runId()).orElseThrow();
		TestCase testCase = testCases.getReferenceById(prepared.testCaseId());
		TestResult result = switch (execution) {
			case Responded responded -> TestResult.responded(run, testCase, responded.exchange());
			case Failed failed -> TestResult.failed(run, testCase, prepared.template().method(), failed.reason(),
					failed.message(), failed.request(), failed.elapsed());
		};
		results.save(result);
		run.complete(Instant.now());
	}

	private static String messageOf(Throwable ex) {
		Throwable root = ex;
		while (root.getCause() != null) {
			root = root.getCause();
		}
		return root == ex || root.getMessage() == null ? ex.getMessage() : ex.getMessage() + " (" + root + ")";
	}

	private record Prepared(long runId, long testCaseId, RequestTemplate template, Map<String, String> variables) {
	}

	private sealed interface Execution permits Responded, Failed {
	}

	private record Responded(HttpExchange exchange) implements Execution {
	}

	private record Failed(ErrorReason reason, String message, ResolvedRequest request, Duration elapsed)
			implements Execution {
	}

}
