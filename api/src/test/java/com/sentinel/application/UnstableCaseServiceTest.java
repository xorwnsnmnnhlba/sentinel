package com.sentinel.application;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.sentinel.TestcontainersConfiguration;
import com.sentinel.domain.Environment;
import com.sentinel.domain.Project;
import com.sentinel.domain.TestCase;
import com.sentinel.domain.TestResult;
import com.sentinel.domain.TestRun;
import com.sentinel.domain.TestSuite;
import com.sentinel.infra.persistence.EnvironmentRepository;
import com.sentinel.infra.persistence.TestCaseRepository;
import com.sentinel.infra.persistence.TestResultRepository;
import com.sentinel.infra.persistence.TestRunRepository;
import com.sentinel.runner.HttpExchange;
import com.sentinel.runner.HttpMethod;
import com.sentinel.runner.RequestExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 불안정 케이스: 기간 안 스케줄 실행에서 실패 구간 2회 이상 또는 재시도한 실행 2회 이상.
 * 2026-09-10 하루 동안 1시간 간격 스케줄 실행을 흉내 냄
 */
@SpringBootTest(properties = { "sentinel.schedule.enabled=false", "sentinel.runner.retry.delay=0s" })
@Import({ TestcontainersConfiguration.class, FakeRequestExecutorConfiguration.class, TestFixtures.class,
		UnstableCaseServiceTest.ClockConfiguration.class })
class UnstableCaseServiceTest {

	/** 2026-09-10 00:00 KST */
	private static final Instant SEPT_10 = Instant.parse("2026-09-09T15:00:00Z");

	private static final LocalDate DAY = LocalDate.of(2026, 9, 10);

	@Autowired
	private UnstableCaseService service;

	@Autowired
	private TestRunService testRunService;

	@Autowired
	private FakeRequestExecutor executor;

	@Autowired
	private TestFixtures fixtures;

	@Autowired
	private TestCaseRepository testCases;

	@Autowired
	private TestRunRepository runs;

	@Autowired
	private TestResultRepository results;

	@Autowired
	private EnvironmentRepository environments;

	@Autowired
	private Transactions transactions;

	@Autowired
	private MutableClock clock;

	private TestSuite suite;

	private Environment dev;

	private int hour;

	@BeforeEach
	void setUp() {
		Project project = fixtures.project();
		dev = fixtures.environment(project, "dev", Map.of());
		suite = fixtures.suite(project);
		hour = 0;
	}

	@AfterEach
	void cleanUp() {
		fixtures.deleteAll();
		executor.reset();
	}

	@Test
	void findsCasesThatKeepFailingAndRecoveringOrNeedRetries() {
		TestCase flaky = caseAt("/flaky");
		TestCase outage = caseAt("/outage");
		TestCase shaky = caseAt("/shaky");
		TestCase once = caseAt("/once");
		caseAt("/steady");

		// 시간 순 판정 (F 실패, R 재시도 뒤 통과, 그 외 통과)
		scheduled(Set.of(), Set.of("/shaky"));
		scheduled(Set.of("/flaky", "/outage"), Set.of("/once"));
		scheduled(Set.of("/outage"), Set.of("/shaky"));
		scheduled(Set.of("/flaky"), Set.of());
		scheduled(Set.of(), Set.of());

		List<UnstableCaseService.UnstableCase> unstable = service.list(DAY, DAY, null).cases();

		// 장애 1번(/outage)과 재시도 1번(/once)은 제외. 실패 구간이 많은 순
		assertThat(unstable).extracting(found -> found.testCase().id(), UnstableCaseService.UnstableCase::runs,
				UnstableCaseService.UnstableCase::failures, UnstableCaseService.UnstableCase::failureEpisodes,
				UnstableCaseService.UnstableCase::retriedRuns)
			.containsExactly(tuple(flaky.getId(), 5L, 2L, 2L, 0L), tuple(shaky.getId(), 5L, 0L, 0L, 2L));
		assertThat(unstable.getFirst().suite()).isEqualTo(new IdName(suite.getId(), suite.getName()));
		assertThat(unstable.getFirst().environment()).isEqualTo(new IdName(dev.getId(), "dev"));
		assertThat(List.of(outage.getId(), once.getId())).doesNotContainAnyElementsOf(
				unstable.stream().map(found -> found.testCase().id()).toList());
	}

	@Test
	void countsAFailureAtTheStartOfThePeriodAsAnEpisode() {
		TestCase flaky = caseAt("/flaky");
		scheduled(Set.of("/flaky"), Set.of());
		scheduled(Set.of(), Set.of());
		scheduled(Set.of("/flaky"), Set.of());

		// 기간 첫 실행의 실패도 실패 구간 1 (직전 판정이 없으면 통과로 봄)
		assertThat(service.list(DAY, DAY, null).cases()).singleElement().satisfies(found -> {
			assertThat(found.testCase().id()).isEqualTo(flaky.getId());
			assertThat(found.failureEpisodes()).isEqualTo(2);
		});
	}

	@Test
	void judgesEachEnvironmentSeparatelyAndIgnoresManualRunsAndOtherDays() {
		caseAt("/flaky");
		Environment staging = fixtures.environment(suite.getProject(), "staging", Map.of());
		// dev와 staging에서 번갈아 실패하면 환경마다 실패 구간 1
		scheduledIn(dev, Set.of("/flaky"), Set.of());
		scheduledIn(staging, Set.of(), Set.of());
		scheduledIn(dev, Set.of(), Set.of());
		scheduledIn(staging, Set.of("/flaky"), Set.of());
		// 수동 실행의 실패는 세지 않음
		manual(Set.of());
		manual(Set.of("/flaky"));
		// 다음 날 실패는 기간 밖
		clock.set(SEPT_10.plus(Duration.ofDays(1)));
		failNext(Set.of("/flaky"), Set.of());
		testRunService.runSuite(suite.getId(), dev.getId(), TestRun.Trigger.SCHEDULED, null);

		assertThat(service.list(DAY, DAY, null).cases()).isEmpty();
	}

	@Test
	void comparesWithThePreviousRunOfTheSameEnvironment() {
		caseAt("/flaky");
		Environment staging = fixtures.environment(suite.getProject(), "staging", Map.of());
		// dev에서 두 번 이어서 실패 (사이의 staging 통과는 dev의 앞 실행이 아님) → 실패 구간 1
		scheduledIn(dev, Set.of("/flaky"), Set.of());
		scheduledIn(staging, Set.of(), Set.of());
		scheduledIn(dev, Set.of("/flaky"), Set.of());

		assertThat(service.list(DAY, DAY, null).cases()).isEmpty();
	}

	@Test
	void leavesOutScheduledRunsThatDidNotComplete() {
		TestCase flaky = caseAt("/flaky");
		scheduled(Set.of(), Set.of());
		// 중간에 멈춘 스케줄 실행의 실패는 세지 않음 (세면 실패 구간 2)
		clock.set(SEPT_10.plus(Duration.ofHours(++hour)));
		TestRun aborted = runs.save(TestRun.start(suite, dev, TestRun.Trigger.SCHEDULED, clock.instant()));
		aborted.abort(clock.instant());
		runs.save(aborted);
		results.save(TestResult.failed(aborted, flaky, HttpMethod.GET, TestResult.ErrorReason.TIMEOUT, "timeout",
				null, Duration.ofSeconds(30)));
		transactions.writeWithoutResult(status -> runs.incrementErrors(aborted.getId()));
		scheduled(Set.of(), Set.of());
		scheduled(Set.of("/flaky"), Set.of());

		assertThat(service.list(DAY, DAY, null).cases()).isEmpty();
	}

	@Test
	void leavesOutDeletedEnvironments() {
		caseAt("/flaky");
		scheduled(Set.of("/flaky"), Set.of());
		scheduled(Set.of(), Set.of());
		scheduled(Set.of("/flaky"), Set.of());
		assertThat(service.list(DAY, DAY, null).cases()).hasSize(1);

		dev.archive(Instant.now());
		environments.save(dev);

		assertThat(service.list(DAY, DAY, null).cases()).isEmpty();
	}

	@Test
	void leavesOutDeletedCasesAndOtherSuitesWhenFiltered() {
		TestCase flaky = caseAt("/flaky");
		TestCase removed = caseAt("/removed");
		scheduled(Set.of("/flaky", "/removed"), Set.of());
		scheduled(Set.of(), Set.of());
		scheduled(Set.of("/flaky", "/removed"), Set.of());
		removed.archive(Instant.now());
		testCases.save(removed);

		assertThat(service.list(DAY, DAY, null).cases()).extracting(found -> found.testCase().id())
			.containsExactly(flaky.getId());
		assertThat(service.list(DAY, DAY, suite.getId()).cases()).hasSize(1);
		assertThat(service.list(DAY, DAY, -1L).cases()).isEmpty();
	}

	private TestCase caseAt(String path) {
		return fixtures.testCase(suite, HttpMethod.GET, path, null, null);
	}

	private void scheduled(Set<String> failing, Set<String> retried) {
		scheduledIn(dev, failing, retried);
	}

	private void scheduledIn(Environment environment, Set<String> failing, Set<String> retried) {
		clock.set(SEPT_10.plus(Duration.ofHours(++hour)));
		failNext(failing, retried);
		testRunService.runSuite(suite.getId(), environment.getId(), TestRun.Trigger.SCHEDULED, null);
	}

	private void manual(Set<String> failing) {
		clock.set(SEPT_10.plus(Duration.ofHours(++hour)));
		failNext(failing, Set.of());
		testRunService.runSuite(suite.getId(), dev.getId(), TestRun.Trigger.MANUAL, fixtures.user().getId());
	}

	/** failing은 500, retried는 첫 전송만 연결 실패 뒤 200 */
	private void failNext(Set<String> failing, Set<String> retried) {
		Set<String> refused = new HashSet<>();
		executor.respondWith(request -> {
			String path = request.uri().getPath();
			if (retried.contains(path) && refused.add(path)) {
				throw new RequestExecutionException(RequestExecutionException.Reason.CONNECTION_FAILED, request,
						Duration.ofMillis(5), null);
			}
			return new HttpExchange(request, failing.contains(path) ? 500 : 200, Map.of(), "", Duration.ofMillis(5));
		});
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class ClockConfiguration {

		@Bean
		@Primary
		MutableClock mutableClock() {
			return new MutableClock(SEPT_10);
		}

	}

}
