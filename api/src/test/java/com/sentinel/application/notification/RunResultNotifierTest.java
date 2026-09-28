package com.sentinel.application.notification;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.sentinel.TestcontainersConfiguration;
import com.sentinel.application.FakeRequestExecutor;
import com.sentinel.application.FakeRequestExecutorConfiguration;
import com.sentinel.application.MutableClock;
import com.sentinel.application.TestFixtures;
import com.sentinel.application.TestRunService;
import com.sentinel.domain.Environment;
import com.sentinel.domain.Project;
import com.sentinel.domain.TestCase;
import com.sentinel.domain.TestResult;
import com.sentinel.domain.TestRun;
import com.sentinel.domain.TestSuite;
import com.sentinel.infra.persistence.TestResultRepository;
import com.sentinel.infra.persistence.TestRunRepository;
import com.sentinel.runner.HttpMethod;
import com.sentinel.runner.assertion.StatusCodeAssertion;
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

/**
 * 스케줄 실행 결과 알림의 연속 실패·복구 판단. 같은 스위트·환경의 이전 스케줄 실행과 비교
 */
@SpringBootTest(properties = "sentinel.schedule.enabled=false")
@Import({ TestcontainersConfiguration.class, FakeRequestExecutorConfiguration.class, TestFixtures.class,
		RunResultNotifierTest.TestBeans.class })
class RunResultNotifierTest {

	/** 2026-09-24 09:10 KST */
	private static final Instant NINE_TEN = Instant.parse("2026-09-24T00:10:00Z");

	@Autowired
	private RunResultNotifier runResultNotifier;

	@Autowired
	private TestRunService testRunService;

	@Autowired
	private FakeRequestExecutor executor;

	@Autowired
	private TestFixtures fixtures;

	@Autowired
	private MutableClock clock;

	@Autowired
	private RecordingNotifier notifier;

	@Autowired
	private TestRunRepository runs;

	@Autowired
	private TestResultRepository results;

	private TestSuite suite;

	private Environment dev;

	private Environment staging;

	private TestCase a;

	private TestCase b;

	@BeforeEach
	void setUp() {
		Project project = fixtures.project();
		dev = fixtures.environment(project, Map.of());
		staging = fixtures.environment(project, "staging", Map.of());
		suite = fixtures.suite(project);
		a = fixtures.testCase(suite, HttpMethod.GET, "/a", null, null, List.of(new StatusCodeAssertion(List.of("200"))));
		b = fixtures.testCase(suite, HttpMethod.GET, "/b", null, null, List.of(new StatusCodeAssertion(List.of("200"))));
	}

	@AfterEach
	void cleanUp() {
		fixtures.deleteAll();
		executor.reset();
		notifier.reset();
	}

	@Test
	void tracksNewAndOngoingFailuresAndAnnouncesRecovery() {
		assertThat(scheduled(0, Set.of())).isEqualTo(RunResultNotifier.Sent.NONE);

		assertThat(scheduled(1, Set.of("/a"))).isEqualTo(RunResultNotifier.Sent.FAILURE);
		assertThat(lastTitle()).endsWith("2건 중 1건 실패 (새로 실패 1건)");
		assertThat(lastLines()).first().asString().startsWith("[새로 실패] " + a.getName() + " · GET /a → ");

		// 수동 실행과 다른 환경의 실행은 비교 대상에서 제외 (연속 실패가 끊기지 않음)
		run(Duration.ofMinutes(80), dev, TestRun.Trigger.MANUAL, Set.of());
		run(Duration.ofMinutes(90), staging, TestRun.Trigger.SCHEDULED, Set.of());

		assertThat(scheduled(2, Set.of("/a", "/b"))).isEqualTo(RunResultNotifier.Sent.FAILURE);
		assertThat(lastTitle()).endsWith("2건 중 2건 실패 (새로 실패 1건)");
		// 새로 실패한 케이스가 앞
		assertThat(lastLines()).first().asString().startsWith("[새로 실패] " + b.getName());
		assertThat(lastLines().get(1)).startsWith("[2회째 · 09-24 10:10부터] " + a.getName());

		assertThat(scheduled(3, Set.of("/a"))).isEqualTo(RunResultNotifier.Sent.FAILURE);
		assertThat(lastTitle()).endsWith("2건 중 1건 실패 (계속 실패 중)");
		assertThat(lastLines()).first().asString().startsWith("[3회째 · 09-24 10:10부터] " + a.getName());

		assertThat(scheduled(4, Set.of())).isEqualTo(RunResultNotifier.Sent.RECOVERY);
		assertThat(lastTitle()).endsWith(" - 복구, 2건 모두 통과");
		// 직전 실행에서 실패한 케이스만, 실패는 10:10부터 스케줄 실행 3회
		assertThat(lastLines()).containsExactly("다시 통과: " + a.getName(),
				"실패 지속: 스케줄 실행 3회, 09-24 10:10부터 3시간 0분");

		// 계속 통과하면 알리지 않음
		assertThat(scheduled(5, Set.of())).isEqualTo(RunResultNotifier.Sent.NONE);
		assertThat(notifier.sent()).hasSize(4);

		// 통과를 사이에 두고 다시 실패하면 연속이 아닌 새 실패
		assertThat(scheduled(6, Set.of("/a"))).isEqualTo(RunResultNotifier.Sent.FAILURE);
		assertThat(lastLines()).first().asString().startsWith("[새로 실패] " + a.getName());
	}

	@Test
	void staysSilentForCanceledRuns() {
		// 실패 결과가 있어도 사람이 멈춘 실행이면 알리지 않음
		TestRun run = runs.save(TestRun.start(suite, dev, TestRun.Trigger.SCHEDULED, NINE_TEN));
		results.save(TestResult.failed(run, a, HttpMethod.GET, TestResult.ErrorReason.TIMEOUT, "timeout", null, null));
		testRunService.cancel(run.getId(), fixtures.user().getId());

		assertThat(runResultNotifier.notifyResult(run.getId())).isEqualTo(RunResultNotifier.Sent.NONE);
		assertThat(notifier.sent()).isEmpty();
	}

	@Test
	void capsLongStreaks() {
		for (int hour = 0; hour <= RunResultNotifier.LOOKBACK; hour++) {
			scheduled(hour, Set.of("/a"));
		}

		// 확인 범위(이전 20회)가 모두 실패면 "이상"
		assertThat(lastLines()).first().asString().startsWith("[21회 이상 · 09-24 09:10부터] " + a.getName());
	}

	/** 09:10 + hours 시각의 스케줄 실행 후 알림 */
	private RunResultNotifier.Sent scheduled(int hours, Set<String> failingPaths) {
		long runId = run(Duration.ofHours(hours), dev, TestRun.Trigger.SCHEDULED, failingPaths);
		return runResultNotifier.notifyResult(runId);
	}

	private long run(Duration after, Environment environment, TestRun.Trigger trigger, Set<String> failingPaths) {
		clock.set(NINE_TEN.plus(after));
		executor.respondStatus(request -> failingPaths.contains(request.uri().getPath()) ? 500 : 200);
		return testRunService.runSuite(suite.getId(), environment.getId(), trigger,
				trigger == TestRun.Trigger.MANUAL ? fixtures.user().getId() : null);
	}

	private String lastTitle() {
		return notifier.sent().getLast().title();
	}

	private List<String> lastLines() {
		return notifier.sent().getLast().lines();
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class TestBeans {

		@Bean
		@Primary
		MutableClock mutableClock() {
			return new MutableClock(NINE_TEN);
		}

		@Bean
		@Primary
		RecordingNotifier recordingNotifier() {
			return new RecordingNotifier();
		}

	}

}
