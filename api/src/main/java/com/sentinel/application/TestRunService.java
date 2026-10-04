package com.sentinel.application;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import com.sentinel.application.drift.SchemaDriftService;
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
import com.sentinel.infra.persistence.TestResultQueries;
import com.sentinel.infra.persistence.TestResultRepository;
import com.sentinel.infra.persistence.TestRunRepository;
import com.sentinel.infra.persistence.UserRepository;
import com.sentinel.infra.persistence.TestSuiteRepository;
import com.sentinel.runner.HttpExchange;
import com.sentinel.runner.RequestExecutionException;
import com.sentinel.runner.RequestExecutor;
import com.sentinel.runner.RequestTemplate;
import com.sentinel.runner.ResolvedRequest;
import com.sentinel.runner.TransportRetry;
import com.sentinel.runner.UnresolvedVariableException;
import com.sentinel.runner.assertion.Assertion;
import com.sentinel.runner.assertion.AssertionEvaluator;
import com.sentinel.application.secret.SecretCipher;
import com.sentinel.application.secret.SecretDecryptionException;
import com.sentinel.application.secret.SecretKeyMissingException;
import com.sentinel.runner.assertion.AssertionResult;
import com.sentinel.runner.extract.Extractor;
import com.sentinel.runner.extract.Extractor.Extraction;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 실행 흐름: 준비(트랜잭션) → 케이스별 전송(트랜잭션 밖) + 결과 기록(케이스별 트랜잭션) → 완료 처리(트랜잭션).
 * <p>
 * HTTP 호출 동안 DB 커넥션을 점유하지 않기 위해 전송 구간을 트랜잭션에서 분리. 커넥션 풀 크기 5 전제.
 * 스위트 내 케이스는 실행 순서(run_order, 같으면 ID 순)대로 순차 실행하여 GW 동시 호출 없음.
 * <p>
 * 응답 값 추출: 케이스의 추출 규칙으로 얻은 값은 같은 실행에서 뒤에 실행하는 케이스의 변수가 됨 (환경 변수보다 우선).
 * 추출에 실패하거나 응답을 받지 못하면 그 변수를 비워 뒤 케이스가 환경 변수의 같은 이름 값으로 조용히 실행되지 않고
 * 미해결 변수 오류가 됨. 가림 지정 값은 이후 이력에서 시크릿처럼 가림.
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

	private final SchemaDriftService schemaDrift;

	private final BackgroundRuns backgroundRuns;

	private final ManualRunSlots manualRunSlots;

	private final TransportRetry transportRetry;

	/**
	 * 스위트 전체 수동 실행 시작. 실행을 만들고 바로 반환하며 케이스 실행은 백그라운드에서 진행 (진행 상황은 {@link #getRun})
	 * @return 생성된 실행 ID
	 * @throws TooManyRunsException 동시 수동 실행 한도 도달 (실행을 만들지 않음)
	 * @throws NotFoundException 스위트 또는 환경 미존재
	 * @throws InvalidRequestException 스위트와 환경의 프로젝트 불일치, 시크릿 복호화 불가
	 */
	public long startSuite(long suiteId, long environmentId, long triggeredBy) {
		return launch(() -> prepareSuite(suiteId, environmentId, TestRun.Trigger.MANUAL, triggeredBy));
	}

	/** 케이스 단건 수동 실행 시작. 예외는 {@link #startSuite}와 같음 (케이스 미존재 포함) */
	public long startTestCase(long testCaseId, long environmentId, long triggeredBy) {
		return launch(() -> prepareTestCase(testCaseId, environmentId, triggeredBy));
	}

	/** 여기까지 수동 실행 시작. 예외는 {@link #startSuite}와 같음 (케이스 미존재 포함) */
	public long startThroughCase(long testCaseId, long environmentId, long triggeredBy) {
		return launch(() -> prepareThroughCase(testCaseId, environmentId, triggeredBy));
	}

	/**
	 * 실패 다시 실행 시작. 원래 실행과 같은 환경에서 실패·오류가 난 케이스만 실행 순서대로 다시 실행.
	 * 그 케이스가 쓰는 추출 값을 만드는 앞 케이스는 함께 실행 ({@link RerunSelection}). 삭제된 케이스는 제외.
	 * 예외는 {@link #startSuite}와 같음 (원래 실행 미존재, 원래 실행 뒤 삭제된 스위트·환경 포함)
	 * @throws ConflictException 원래 실행이 진행 중이거나 다시 실행할 실패 케이스가 없음
	 */
	public long startRerun(long runId, long triggeredBy) {
		return launch(() -> prepareRerun(runId, triggeredBy));
	}

	/** 자리를 잡고 실행을 만든 뒤(요청 스레드) 케이스 실행은 백그라운드로. 준비가 실패하면 실행 없이 자리 반납 */
	private long launch(Supplier<Prepared> prepare) {
		manualRunSlots.acquire();
		Prepared prepared;
		try {
			prepared = prepare.get();
		}
		catch (RuntimeException ex) {
			manualRunSlots.release();
			throw ex;
		}
		backgroundRuns.submit(() -> {
			try {
				executeAndRecord(prepared);
			}
			finally {
				manualRunSlots.release();
			}
		});
		return prepared.runId();
	}

	/**
	 * 케이스 1건 수동 실행. 응답 미수신·변수 누락도 실패 결과로 기록하며 예외로 전파하지 않음.
	 * @return 생성된 실행 ID
	 * @throws NotFoundException 케이스 또는 환경 미존재
	 * @throws InvalidRequestException 케이스와 환경의 프로젝트 불일치
	 * @param triggeredBy 실행을 시작한 사용자
	 */
	long runTestCase(long testCaseId, long environmentId, long triggeredBy) {
		return executeAndRecord(prepareTestCase(testCaseId, environmentId, triggeredBy));
	}

	private Prepared prepareTestCase(long testCaseId, long environmentId, long triggeredBy) {
		return transactions.write(status -> {
			TestCase testCase = testCases.findByIdAndArchivedAtIsNull(testCaseId)
				.orElseThrow(() -> new NotFoundException("test case", testCaseId));
			return start(List.of(testCase), TestRun.startCase(testCase, findEnvironment(environmentId),
					TestRun.Trigger.MANUAL, clock.instant(), triggeredBy));
		});
	}

	/**
	 * 여기까지 실행. 케이스가 속한 스위트의 처음부터 이 케이스까지 실행 순서대로 수동 실행.
	 * 앞 케이스가 추출하는 값을 쓰는 케이스를 스위트 전체 실행 없이 확인하기 위함 (뒤 케이스는 실행하지 않음)
	 * @return 생성된 실행 ID
	 * @throws NotFoundException 케이스 또는 환경 미존재
	 * @throws InvalidRequestException 케이스와 환경의 프로젝트 불일치
	 * @param triggeredBy 실행을 시작한 사용자
	 */
	long runThroughCase(long testCaseId, long environmentId, long triggeredBy) {
		return executeAndRecord(prepareThroughCase(testCaseId, environmentId, triggeredBy));
	}

	private Prepared prepareThroughCase(long testCaseId, long environmentId, long triggeredBy) {
		return transactions.write(status -> {
			TestCase through = testCases.findByIdAndArchivedAtIsNull(testCaseId)
				.orElseThrow(() -> new NotFoundException("test case", testCaseId));
			List<TestCase> suiteCases = testCases
				.findActiveInRunOrder(through.getSuite().getId());
			int last = IntStream.range(0, suiteCases.size())
				.filter(index -> BaseEntity.same(suiteCases.get(index), through))
				.findFirst()
				.orElseThrow();
			return start(suiteCases.subList(0, last + 1), TestRun.startThrough(through, findEnvironment(environmentId),
					TestRun.Trigger.MANUAL, clock.instant(), triggeredBy));
		});
	}

	/**
	 * 실패 다시 실행 (호출 스레드에서 끝까지 실행). 동작과 예외는 {@link #startRerun}과 같음
	 * @return 생성된 실행 ID
	 */
	long runRerun(long runId, long triggeredBy) {
		return executeAndRecord(prepareRerun(runId, triggeredBy));
	}

	private Prepared prepareRerun(long runId, long triggeredBy) {
		return transactions.write(status -> {
			TestRun original = runs.findById(runId).orElseThrow(() -> new NotFoundException("test run", runId));
			if (original.getStatus() == TestRun.Status.RUNNING) {
				throw new ConflictException("test run " + runId + " is still running");
			}
			// 원래 실행 뒤 삭제(보관)된 스위트·환경으로는 실행하지 않음
			if (original.getSuite().isArchived()) {
				throw new NotFoundException("test suite", original.getSuite().getId());
			}
			if (original.getEnvironment().isArchived()) {
				throw new NotFoundException("environment", original.getEnvironment().getId());
			}
			Set<Long> failed = failedCaseIds(runId);
			List<TestCase> suiteCases = testCases.findActiveInRunOrder(original.getSuite().getId());
			if (suiteCases.stream().noneMatch(testCase -> failed.contains(testCase.getId()))) {
				throw new ConflictException("test run " + runId + " has no failed cases to rerun");
			}
			Set<Long> selected = RerunSelection.select(suiteCases.stream().map(TestRunService::stepOf).toList(), failed);
			return start(suiteCases.stream().filter(testCase -> selected.contains(testCase.getId())).toList(),
					TestRun.startRerun(original, clock.instant(), triggeredBy));
		});
	}

	/** 실행에서 판정 실패·오류가 난 케이스 */
	private Set<Long> failedCaseIds(long runId) {
		return results.findCaseVerdictsByRunIds(List.of(runId))
			.stream()
			.filter(verdict -> verdict.verdict() == TestResult.Verdict.FAILED
					|| verdict.verdict() == TestResult.Verdict.ERROR)
			.map(TestResultQueries.CaseVerdict::caseId)
			.collect(Collectors.toUnmodifiableSet());
	}

	private static RerunSelection.Step stepOf(TestCase testCase) {
		RequestSpec spec = testCase.getRequest();
		// 변수 이름만 필요하므로 기본 URL은 치환 대상이 아닌 임의 값
		Set<String> uses = new RequestTemplate(spec.getMethod(), "", spec.getPath(), spec.getHeaders(), spec.getBody())
			.variableNames();
		return new RerunSelection.Step(testCase.getId(), uses,
				testCase.getExtractors().stream().map(Extractor::name).collect(Collectors.toUnmodifiableSet()));
	}

	/**
	 * 스위트 전체 실행. 케이스가 없는 스위트도 결과 0건의 완료된 실행으로 기록.
	 * @return 생성된 실행 ID
	 * @throws NotFoundException 스위트 또는 환경 미존재
	 * @throws InvalidRequestException 스위트와 환경의 프로젝트 불일치
	 * @param triggeredBy 수동 실행을 시작한 사용자. 스케줄 실행은 null
	 */
	long runSuite(long suiteId, long environmentId, TestRun.Trigger trigger, Long triggeredBy) {
		return executeAndRecord(prepareSuite(suiteId, environmentId, trigger, triggeredBy));
	}

	private Prepared prepareSuite(long suiteId, long environmentId, TestRun.Trigger trigger, Long triggeredBy) {
		return transactions.write(status -> {
			TestSuite suite = suites.findByIdAndArchivedAtIsNull(suiteId).orElseThrow(() -> new NotFoundException("test suite", suiteId));
			return start(testCases.findActiveInRunOrder(suiteId),
					TestRun.start(suite, findEnvironment(environmentId), trigger, clock.instant(), triggeredBy));
		});
	}

	public TestRunDetail getRun(long runId) {
		return transactions.readOnly(status -> {
			TestRun run = runs.findById(runId).orElseThrow(() -> new NotFoundException("test run", runId));
			List<TestResult> runResults = results.findWithTestCaseByRunId(runId);
			return TestRunDetail.of(run, runResults, userName(run.getTriggeredBy()), userName(run.getCanceledBy()),
					caseName(run.getTestCaseId()), caseName(run.getThroughCaseId()),
					schemaDrift.driftOf(run, runResults), previous(run),
					run.isRerun() ? failedCaseIds(run.getRerunOfRunId()) : null);
		});
	}

	/**
	 * 비교 기준인 직전 실행. 스위트 전체 실행끼리만 비교 (단건·여기까지·실패 다시 실행은 일부 케이스라 비교하지 않음).
	 * 같은 스위트·환경의 완료된 실행 중 바로 앞 실행 (수동·스케줄 무관). 없으면 null
	 */
	private TestRunDetail.Previous previous(TestRun run) {
		if (!run.isWholeSuite()) {
			return null;
		}
		return runs
			.findPreviousWholeRuns(run.getSuite().getId(), run.getEnvironment().getId(), TestRun.Status.COMPLETED,
					run.getStartedAt(), run.getId(), 1)
			.stream()
			.findFirst()
			.map(previous -> {
				Map<Long, TestResult.Verdict> verdicts = new HashMap<>();
				results.findCaseVerdictsByRunIds(List.of(previous.getId()))
					.stream()
					.filter(verdict -> verdict.verdict() != null)
					.forEach(verdict -> verdicts.put(verdict.caseId(), verdict.verdict()));
				return new TestRunDetail.Previous(
						new TestRunDetail.PreviousRun(previous.getId(), previous.getStartedAt()), verdicts);
			})
			.orElse(null);
	}

	/** 단건 실행·여기까지 실행의 대상 케이스. 해당 없으면 null, 삭제(보관)된 케이스도 이력 표시를 위해 조회 */
	private IdName caseName(Long caseId) {
		if (caseId == null) {
			return null;
		}
		return testCases.findById(caseId)
			.map(testCase -> new IdName(testCase.getId(), testCase.getName()))
			.orElse(null);
	}

	/** 실행·중지한 사용자 이름. 없으면(스케줄 실행, 중지되지 않은 실행) null, 삭제(보관)된 사용자도 이력 표시를 위해 조회 */
	private IdName userName(Long userId) {
		if (userId == null) {
			return null;
		}
		return users.findById(userId)
			.map(user -> new IdName(user.getId(), user.getEmail()))
			.orElse(null);
	}

	private Environment findEnvironment(long environmentId) {
		return environments.findByIdAndArchivedAtIsNull(environmentId)
			.orElseThrow(() -> new NotFoundException("environment", environmentId));
	}

	/**
	 * @param cases 실행할 케이스 (실행 순서)
	 * @param started 저장 전 실행 (스위트·환경·종류는 호출자가 지정)
	 */
	private Prepared start(List<TestCase> cases, TestRun started) {
		TestSuite suite = started.getSuite();
		Environment environment = started.getEnvironment();
		if (!BaseEntity.same(suite.getProject(), environment.getProject())) {
			throw new InvalidRequestException("environment " + environment.getId()
					+ " does not belong to the project of test suite " + suite.getId());
		}
		started.plan(cases.size());
		TestRun run = runs.save(started);
		List<PreparedCase> preparedCases = cases.stream()
			.map(testCase -> new PreparedCase(testCase.getId(), templateOf(testCase.getRequest(), environment),
					testCase.getAssertions(), testCase.getExtractors()))
			.toList();
		// 단건 실행 등에서 다른 케이스가 추출하는 변수를 쓰면 미해결 변수 메시지로 안내하기 위해 스위트 전체의 추출 변수 이름 수집
		List<TestCase> suiteCases = started.isWholeSuite() ? cases : testCases.findActiveInRunOrder(suite.getId());
		Set<String> extractedInSuite = suiteCases.stream()
			.flatMap(testCase -> testCase.getExtractors().stream())
			.map(Extractor::name)
			.collect(Collectors.toUnmodifiableSet());
		Map<String, String> secrets = decrypt(environment);
		Map<String, String> values = new LinkedHashMap<>(environment.getVariables());
		values.putAll(secrets);
		return new Prepared(run.getId(), suite.getId(), run.getTrigger(), preparedCases, Map.copyOf(values),
				Set.copyOf(secrets.values()), extractedInSuite);
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
		// 추출한 값이 더해지는 실행 범위의 변수와 가릴 값
		Map<String, String> variables = new LinkedHashMap<>(prepared.variables());
		Set<String> secretValues = new HashSet<>(prepared.secretValues());
		for (PreparedCase preparedCase : prepared.cases()) {
			// 중지 요청은 케이스 사이에서 확인. 이미 보낸 요청은 되돌릴 수 없으므로 진행 중이던 케이스는 끝까지 기록
			if (runs.findStatus(prepared.runId()) != TestRun.Status.RUNNING) {
				break;
			}
			Execution execution = extract(preparedCase,
					execute(preparedCase, variables, prepared.extractedInSuite()), variables, secretValues);
			if (execution.retries() > 0) {
				log.info("Run {} case {} was sent {} more time(s) after no response: {}", prepared.runId(),
						preparedCase.testCaseId(), execution.retries(),
						execution instanceof Responded ? "responded" : "still no response");
			}
			Set<String> masked = Set.copyOf(secretValues);
			TestResult.Verdict verdict = transactions.write(status -> {
				TestResult.Verdict saved = results.save(resultOf(prepared.runId(), preparedCase, execution, masked))
					.getVerdict();
				// 실행의 판정 수·재시도 수도 같은 트랜잭션에서 올려 결과와 어긋나지 않음 (중단·중지된 실행도 저장된 결과 기준)
				countVerdict(prepared.runId(), saved);
				if (execution.retries() > 0) {
					runs.incrementRetried(prepared.runId());
				}
				return saved;
			});
			counts.merge(verdict, 1, Integer::sum);
		}
		boolean completed = transactions.write(status -> runs.completeIfRunning(prepared.runId(), clock.instant()) == 1);
		log.info("Run {} {}: trigger={} suite={} total={} passed={} failed={} errors={} elapsed={}ms",
				prepared.runId(), completed ? "completed" : "canceled", prepared.trigger(), prepared.suiteId(),
				counts.values().stream().mapToInt(Integer::intValue).sum(),
				counts.getOrDefault(TestResult.Verdict.PASSED, 0), counts.getOrDefault(TestResult.Verdict.FAILED, 0),
				counts.getOrDefault(TestResult.Verdict.ERROR, 0), Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
		return prepared.runId();
	}

	/** 판정 없는 결과(null)는 세지 않음 */
	private void countVerdict(long runId, TestResult.Verdict verdict) {
		if (verdict == null) {
			return;
		}
		switch (verdict) {
			case PASSED -> runs.incrementPassed(runId);
			case FAILED -> runs.incrementFailed(runId);
			case ERROR -> runs.incrementErrors(runId);
		}
	}

	/**
	 * 진행 중인 실행 중지. 진행 중이던 케이스는 끝까지 기록하고 이후 케이스는 보내지 않음 (수동·스케줄 실행 모두)
	 * @param canceledBy 중지한 사용자
	 * @throws NotFoundException 실행 미존재
	 * @throws ConflictException 이미 끝난 실행 (완료·중단·중지)
	 */
	public void cancel(long runId, long canceledBy) {
		transactions.writeWithoutResult(status -> {
			TestRun run = runs.findById(runId).orElseThrow(() -> new NotFoundException("test run", runId));
			if (runs.cancelIfRunning(runId, canceledBy, clock.instant()) == 0) {
				throw new ConflictException("test run " + runId + " is already " + run.getStatus());
			}
		});
	}

	/**
	 * 응답에서 값을 추출해 {@code variables}·{@code secretValues}에 반영하고 추출 결과를 판정 결과 뒤에 덧붙임.
	 * 실패한 추출과 응답을 받지 못한 케이스의 추출 변수는 비움
	 */
	private static Execution extract(PreparedCase preparedCase, Execution execution, Map<String, String> variables,
			Set<String> secretValues) {
		if (preparedCase.extractors().isEmpty()) {
			return execution;
		}
		if (!(execution instanceof Responded responded)) {
			preparedCase.extractors().forEach(extractor -> variables.remove(extractor.name()));
			return execution;
		}
		List<AssertionResult> assertionResults = new ArrayList<>(responded.assertionResults());
		for (Extractor extractor : preparedCase.extractors()) {
			Extraction extraction = extractor.extract(responded.exchange());
			if (extraction.succeeded()) {
				variables.put(extractor.name(), extraction.value());
				if (extractor.secret()) {
					secretValues.add(extraction.value());
				}
			}
			else {
				variables.remove(extractor.name());
			}
			assertionResults.add(new AssertionResult(extractor.describe(), extraction.succeeded(), extraction.message()));
		}
		return new Responded(responded.exchange(), assertionResults, responded.retries());
	}

	private Execution execute(PreparedCase preparedCase, Map<String, String> variables, Set<String> extractedInSuite) {
		ResolvedRequest request;
		try {
			request = preparedCase.template().resolve(variables);
		}
		catch (UnresolvedVariableException ex) {
			return new Failed(ErrorReason.UNRESOLVED_VARIABLE, unresolvedMessage(ex, extractedInSuite), null, null, 0);
		}
		catch (IllegalArgumentException ex) {
			return new Failed(ErrorReason.INVALID_REQUEST, ex.getMessage(), null, null, 0);
		}

		long startedAt = System.nanoTime();
		try {
			TransportRetry.Sent sent = transportRetry.send(request, executor);
			if (sent.failure() != null) {
				RequestExecutionException ex = sent.failure();
				return new Failed(ErrorReason.valueOf(ex.reason().name()), messageOf(ex), request, ex.elapsed(),
						sent.retries());
			}
			return new Responded(sent.exchange(),
					AssertionEvaluator.evaluate(preparedCase.assertions(), sent.exchange()), sent.retries());
		}
		catch (IllegalArgumentException ex) {
			// JDK HttpClient 제한 헤더(Host, Content-Length 등) 지정 시
			return new Failed(ErrorReason.INVALID_REQUEST, ex.getMessage(), request, null, 0);
		}
		catch (RuntimeException ex) {
			return new Failed(ErrorReason.UNEXPECTED, messageOf(ex), request,
					Duration.ofNanos(System.nanoTime() - startedAt), 0);
		}
	}

	private TestResult resultOf(long runId, PreparedCase preparedCase, Execution execution, Set<String> secretValues) {
		TestRun run = runs.getReferenceById(runId);
		TestCase testCase = testCases.getReferenceById(preparedCase.testCaseId());
		TestResult result = switch (execution) {
			case Responded responded -> TestResult.responded(run, testCase, responded.exchange(),
					responded.assertionResults(), secretValues);
			case Failed failed -> TestResult.failed(run, testCase, preparedCase.template().method(), failed.reason(),
					failed.message(), failed.request(), failed.elapsed(), secretValues);
		};
		result.recordRetries(execution.retries());
		return result;
	}

	/** 다른 케이스가 추출하는 변수가 빠졌으면 추출하는 케이스를 앞에서 함께 실행해야 한다는 안내 추가 */
	private static String unresolvedMessage(UnresolvedVariableException ex, Set<String> extractedInSuite) {
		List<String> extracted = ex.names().stream().filter(extractedInSuite::contains).sorted().toList();
		if (extracted.isEmpty()) {
			return ex.getMessage();
		}
		return ex.getMessage() + ". Extracted by a case in this suite: " + String.join(", ", extracted)
				+ " (set only when the extracting case runs earlier in the same run: run the whole suite or run through"
				+ " this case)";
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
	 * @param extractedInSuite 스위트의 케이스들이 추출하는 변수 이름
	 */
	private record Prepared(long runId, long suiteId, TestRun.Trigger trigger, List<PreparedCase> cases,
			Map<String, String> variables, Set<String> secretValues, Set<String> extractedInSuite) {
	}

	private record PreparedCase(long testCaseId, RequestTemplate template, List<Assertion> assertions,
			List<Extractor> extractors) {
	}

	/** 케이스 1건의 전송 결과. {@code retries}는 응답을 받지 못해 다시 보낸 횟수 */
	private sealed interface Execution permits Responded, Failed {

		int retries();

	}

	private record Responded(HttpExchange exchange, List<AssertionResult> assertionResults, int retries)
			implements Execution {
	}

	private record Failed(ErrorReason reason, String message, ResolvedRequest request, Duration elapsed, int retries)
			implements Execution {
	}

}
