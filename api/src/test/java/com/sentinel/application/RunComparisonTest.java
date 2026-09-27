package com.sentinel.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.sentinel.TestcontainersConfiguration;
import com.sentinel.application.TestRunDetail.Change;
import com.sentinel.domain.Environment;
import com.sentinel.domain.TestCase;
import com.sentinel.domain.TestRun;
import com.sentinel.domain.TestSuite;
import com.sentinel.infra.persistence.TestRunRepository;
import com.sentinel.runner.HttpMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/** 결과 상세의 직전 실행 대비 케이스별 판정 변화 */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, FakeRequestExecutorConfiguration.class, TestFixtures.class })
class RunComparisonTest {

	@Autowired
	private TestRunService service;

	@Autowired
	private FakeRequestExecutor executor;

	@Autowired
	private TestFixtures fixtures;

	@Autowired
	private TestRunRepository runs;

	private TestSuite suite;

	private Environment dev;

	private long tester;

	/** 이 경로들은 500 (실패), 나머지는 200 */
	private Set<String> failing = Set.of();

	@BeforeEach
	void setUp() {
		suite = fixtures.suite(fixtures.project());
		dev = fixtures.environment(suite.getProject(), "dev", Map.of());
		tester = fixtures.user().getId();
		executor.respondStatus(request -> failing.contains(request.uri().getPath()) ? 500 : 200);
	}

	@AfterEach
	void cleanUp() {
		fixtures.deleteAll();
		executor.reset();
	}

	@Test
	void hasNothingToCompareOnTheFirstRun() {
		caseAt("/a");

		TestRunDetail run = service.getRun(runSuite(dev));

		assertThat(run.previousRun()).isNull();
		assertThat(run.results()).extracting(TestRunDetail.Result::change).containsOnlyNulls();
	}

	@Test
	void marksNewFailuresRecoveriesAndNewCases() {
		TestCase broken = caseAt("/broken");
		TestCase fixed = caseAt("/fixed");
		TestCase stillBroken = caseAt("/still");
		TestCase fine = caseAt("/fine");
		failing = Set.of("/fixed", "/still");
		long first = runSuite(dev);

		TestCase added = caseAt("/added");
		failing = Set.of("/broken", "/still");
		TestRunDetail second = service.getRun(runSuite(dev));

		assertThat(second.previousRun().id()).isEqualTo(first);
		assertThat(second.previousRun().startedAt()).isEqualTo(service.getRun(first).startedAt());
		assertThat(changes(second)).containsExactlyInAnyOrderEntriesOf(Map.of(broken.getId(), Change.NEWLY_FAILING,
				fixed.getId(), Change.RECOVERED, stillBroken.getId(), Change.STILL_FAILING, fine.getId(),
				Change.UNCHANGED, added.getId(), Change.NEW));
	}

	@Test
	void comparesWithTheRunRightBeforeEvenWhenViewingAnOldRun() {
		caseAt("/a");
		long first = runSuite(dev);
		long second = runSuite(dev);
		long third = runSuite(dev);

		assertThat(service.getRun(third).previousRun().id()).isEqualTo(second);
		// 오래된 실행은 그 뒤 실행이 아니라 바로 앞 실행과 비교
		assertThat(service.getRun(second).previousRun().id()).isEqualTo(first);
	}

	@Test
	void treatsErrorsAsFailures() {
		TestCase unresolved = fixtures.testCase(suite, HttpMethod.GET, "/{{missing}}", null, null);
		runSuite(dev);

		TestRunDetail second = service.getRun(runSuite(dev));

		assertThat(changes(second)).containsEntry(unresolved.getId(), Change.STILL_FAILING);
	}

	@Test
	void comparesWithThePreviousCompletedWholeRunOfTheSameEnvironment() {
		TestCase first = caseAt("/a");
		caseAt("/b");
		Environment staging = fixtures.environment(suite.getProject(), "staging", Map.of());
		long baseline = runSuite(dev);
		// 비교 대상이 아닌 실행: 다른 환경, 케이스 단건 실행, 여기까지 실행, 끝나지 않은 실행
		runSuite(staging);
		service.runTestCase(first.getId(), dev.getId(), tester);
		service.runThroughCase(first.getId(), dev.getId(), tester);
		runs.save(TestRun.start(suite, dev, TestRun.Trigger.SCHEDULED, Instant.now()));

		TestRunDetail latest = service.getRun(runSuite(dev));

		assertThat(latest.previousRun().id()).isEqualTo(baseline);
	}

	@Test
	void doesNotCompareSingleCaseOrPartialRuns() {
		TestCase first = caseAt("/a");
		runSuite(dev);

		TestRunDetail single = service.getRun(service.runTestCase(first.getId(), dev.getId(), tester));
		TestRunDetail through = service.getRun(service.runThroughCase(first.getId(), dev.getId(), tester));

		for (TestRunDetail partial : List.of(single, through)) {
			assertThat(partial.previousRun()).isNull();
			assertThat(partial.results()).extracting(TestRunDetail.Result::change).containsOnlyNulls();
		}
	}

	@Test
	void usesTheScheduledRunBeforeAManualOne() {
		caseAt("/a");
		long scheduled = service.runSuite(suite.getId(), dev.getId(), TestRun.Trigger.SCHEDULED, null);

		assertThat(service.getRun(runSuite(dev)).previousRun().id()).isEqualTo(scheduled);
	}

	private TestCase caseAt(String path) {
		return fixtures.testCase(suite, HttpMethod.GET, path, null, null);
	}

	private long runSuite(Environment environment) {
		return service.runSuite(suite.getId(), environment.getId(), TestRun.Trigger.MANUAL, tester);
	}

	private static Map<Long, Change> changes(TestRunDetail run) {
		return run.results()
			.stream()
			.collect(Collectors.toMap(TestRunDetail.Result::testCaseId, TestRunDetail.Result::change));
	}

}
