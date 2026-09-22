package com.sentinel.application;

import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import com.sentinel.infra.persistence.UserRepository;
import com.sentinel.infra.persistence.TestSuiteRepository;
import com.sentinel.runner.HttpExchange;
import com.sentinel.runner.RequestExecutionException;
import com.sentinel.runner.RequestExecutor;
import com.sentinel.runner.RequestTemplate;
import com.sentinel.runner.ResolvedRequest;
import com.sentinel.runner.UnresolvedVariableException;
import com.sentinel.runner.assertion.Assertion;
import com.sentinel.runner.assertion.AssertionEvaluator;
import com.sentinel.application.secret.SecretCipher;
import com.sentinel.application.secret.SecretDecryptionException;
import com.sentinel.application.secret.SecretKeyMissingException;
import com.sentinel.runner.assertion.AssertionResult;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 실행 흐름: 준비(트랜잭션) → 케이스별 전송(트랜잭션 밖) + 결과 기록(케이스별 트랜잭션) → 완료 처리(트랜잭션).
 * <p>
 * HTTP 호출 동안 DB 커넥션을 점유하지 않기 위해 전송 구간을 트랜잭션에서 분리. 커넥션 풀 크기 5 전제.
 * 스위트 내 케이스는 ID 순서로 순차 실행하여 GW 동시 호출 없음.
 */
@Service
@RequiredArgsConstructor
public class TestRunService {

	private static final Logger log = LoggerFactory.getLogger(TestRunService.class);

	private final TestSuiteRepository suites;

	private final TestCaseRepository testCases;

	private final EnvironmentRepository environments;

	private final TestRunRepository runs;

	private final TestResultRepository results;

	private final RequestExecutor executor;

	private final Transactions transactions;

	private final UserRepository users;

	private final SecretCipher secretCipher;

	private final Clock clock;

	/**
	 * 케이스 1건 수동 실행. 응답 미수신·변수 누락도 실패 결과로 기록하며 예외로 전파하지 않음.
	 * @return 생성된 실행 ID
	 * @throws NotFoundException 케이스 또는 환경 미존재
	 * @throws InvalidRequestException 케이스와 환경의 프로젝트 불일치
	 * @param triggeredBy 실행을 시작한 사용자
	 */
	public long runTestCase(long testCaseId, long environmentId, long triggeredBy) {
		Prepared prepared = transactions.write(status -> {
			TestCase testCase = testCases.findByIdAndArchivedAtIsNull(testCaseId)
				.orElseThrow(() -> new NotFoundException("test case", testCaseId));
			return start(testCase.getSuite(), findEnvironment(environmentId), List.of(testCase),
					TestRun.Trigger.MANUAL, triggeredBy);
		});
		return executeAndRecord(prepared);
	}

	/**
	 * 스위트 전체 실행. 케이스가 없는 스위트도 결과 0건의 완료된 실행으로 기록.
	 * @return 생성된 실행 ID
	 * @throws NotFoundException 스위트 또는 환경 미존재
	 * @throws InvalidRequestException 스위트와 환경의 프로젝트 불일치
	 * @param triggeredBy 수동 실행을 시작한 사용자. 스케줄 실행은 null
	 */
	public long runSuite(long suiteId, long environmentId, TestRun.Trigger trigger, Long triggeredBy) {
		Prepared prepared = transactions.write(status -> {
			TestSuite suite = suites.findByIdAndArchivedAtIsNull(suiteId).orElseThrow(() -> new NotFoundException("test suite", suiteId));
			return start(suite, findEnvironment(environmentId), testCases.findBySuiteIdAndArchivedAtIsNullOrderById(suiteId), trigger,
					triggeredBy);
		});
		return executeAndRecord(prepared);
	}

	public TestRunDetail getRun(long runId) {
		return transactions.readOnly(status -> {
			TestRun run = runs.findById(runId).orElseThrow(() -> new NotFoundException("test run", runId));
			return TestRunDetail.of(run, results.findWithTestCaseByRunId(runId), triggeredBy(run));
		});
	}

	/** 실행 주체 이름. 스케줄 실행은 null, 삭제(보관)된 사용자도 이력 표시를 위해 조회 */
	private IdName triggeredBy(TestRun run) {
		if (run.getTriggeredBy() == null) {
			return null;
		}
		return users.findById(run.getTriggeredBy())
			.map(user -> new IdName(user.getId(), user.getEmail()))
			.orElse(null);
	}

	private Environment findEnvironment(long environmentId) {
		return environments.findByIdAndArchivedAtIsNull(environmentId)
			.orElseThrow(() -> new NotFoundException("environment", environmentId));
	}

	private Prepared start(TestSuite suite, Environment environment, List<TestCase> cases, TestRun.Trigger trigger,
			Long triggeredBy) {
		if (!BaseEntity.same(suite.getProject(), environment.getProject())) {
			throw new InvalidRequestException("environment " + environment.getId()
					+ " does not belong to the project of test suite " + suite.getId());
		}
		TestRun run = runs.save(TestRun.start(suite, environment, trigger, clock.instant(), triggeredBy));
		List<PreparedCase> preparedCases = cases.stream()
			.map(testCase -> new PreparedCase(testCase.getId(), templateOf(testCase.getRequest(), environment),
					testCase.getAssertions()))
			.toList();
		Map<String, String> secrets = decrypt(environment);
		Map<String, String> values = new LinkedHashMap<>(environment.getVariables());
		values.putAll(secrets);
		return new Prepared(run.getId(), suite.getId(), trigger, preparedCases, Map.copyOf(values),
				Set.copyOf(secrets.values()));
	}

	/**
	 * 시크릿 복호화. 키 미설정·불일치는 실행 전에 거부하여 미해결 변수로 전송되는 일이 없도록 함
	 * @throws InvalidRequestException 키 미설정 또는 복호화 실패. 메시지에 값 미포함
	 */
	private Map<String, String> decrypt(Environment environment) {
		Map<String, String> encrypted = environment.getEncryptedSecrets();
		if (encrypted.isEmpty()) {
			return Map.of();
		}
		Map<String, String> decrypted = new LinkedHashMap<>();
		try {
			encrypted.forEach((name, value) -> decrypted.put(name, secretCipher.decrypt(value)));
		}
		catch (SecretKeyMissingException | SecretDecryptionException ex) {
			throw new InvalidRequestException(
					"environment " + environment.getId() + " has secrets that cannot be read: " + ex.getMessage());
		}
		return decrypted;
	}

	private static RequestTemplate templateOf(RequestSpec spec, Environment environment) {
		return new RequestTemplate(spec.getMethod(), environment.getBaseUrl(), spec.getPath(), spec.getHeaders(),
				spec.getBody());
	}

	private long executeAndRecord(Prepared prepared) {
		long startedAt = System.nanoTime();
		Map<TestResult.Verdict, Integer> counts = new EnumMap<>(TestResult.Verdict.class);
		for (PreparedCase preparedCase : prepared.cases()) {
			Execution execution = execute(preparedCase, prepared.variables());
			TestResult.Verdict verdict = transactions.write(
					status -> results.save(resultOf(prepared.runId(), preparedCase, execution, prepared.secretValues()))
						.getVerdict());
			counts.merge(verdict, 1, Integer::sum);
		}
		transactions.writeWithoutResult(status -> runs.findById(prepared.runId()).orElseThrow().complete(clock.instant()));
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

	private TestResult resultOf(long runId, PreparedCase preparedCase, Execution execution, Set<String> secretValues) {
		TestRun run = runs.getReferenceById(runId);
		TestCase testCase = testCases.getReferenceById(preparedCase.testCaseId());
		return switch (execution) {
			case Responded responded -> TestResult.responded(run, testCase, responded.exchange(),
					responded.assertionResults(), secretValues);
			case Failed failed -> TestResult.failed(run, testCase, preparedCase.template().method(), failed.reason(),
					failed.message(), failed.request(), failed.elapsed(), secretValues);
		};
	}

	private static String messageOf(Throwable ex) {
		Throwable root = ex;
		while (root.getCause() != null) {
			root = root.getCause();
		}
		return root == ex || root.getMessage() == null ? ex.getMessage() : ex.getMessage() + " (" + root + ")";
	}

	/**
	 * @param variables 변수와 복호화한 시크릿
	 * @param secretValues 이력 저장 시 가릴 시크릿 값
	 */
	private record Prepared(long runId, long suiteId, TestRun.Trigger trigger, List<PreparedCase> cases,
			Map<String, String> variables, Set<String> secretValues) {
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
