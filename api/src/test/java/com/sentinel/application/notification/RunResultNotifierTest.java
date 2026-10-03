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
import com.sentinel.application.ScheduleProperties;
import com.sentinel.application.TestFixtures;
import com.sentinel.application.TestRunService;
import com.sentinel.application.Transactions;
import com.sentinel.domain.Environment;
import com.sentinel.domain.Project;
import com.sentinel.domain.TestCase;
import com.sentinel.domain.TestResult;
import com.sentinel.domain.TestRun;
import com.sentinel.domain.TestSuite;
import com.sentinel.infra.persistence.TestResultRepository;
import com.sentinel.infra.persistence.TestRunRepository;
import com.sentinel.runner.HttpExchange;
import com.sentinel.runner.HttpMethod;
import com.sentinel.runner.RequestExecutionException;
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
@SpringBootTest(properties = { "sentinel.schedule.enabled=false", "sentinel.runner.retry.delay=0s" })
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

	@Autowired
	private NotificationChannels channels;

	@Autowired
	private ScheduleProperties scheduleProperties;

	@Autowired
	private Transactions transactions;

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

	@Test
	void remindsOngoingFailuresOncePerRepeatInterval() {
		// 10분 간격 스케줄. 처음 실패는 바로 (폴링이 늦어 실행 시작이 30초 밀림)
		long first = run(Duration.ofSeconds(30), dev, TestRun.Trigger.SCHEDULED, Set.of("/a"));
		assertThat(runResultNotifier.notifyResult(first)).isEqualTo(RunResultNotifier.Sent.FAILURE);
		assertThat(lastLines()).contains("계속 실패하면 1시간마다 다시 알림 (새로 실패·복구는 바로)");

		// 같은 케이스가 계속 실패하는 동안은 1시간까지 생략
		for (int minutes = 10; minutes <= 50; minutes += 10) {
			assertThat(every10(minutes, Set.of("/a"))).isEqualTo(RunResultNotifier.Sent.SUPPRESSED);
		}
		assertThat(notifier.sent()).hasSize(1);

		// 1시간 뒤 다시 알림. 앞 발송 실행이 늦게 시작했어도 폴링 주기만큼 여유를 두어 제때
		assertThat(every10(60, Set.of("/a"))).isEqualTo(RunResultNotifier.Sent.FAILURE);
		assertThat(lastTitle()).endsWith("2건 중 1건 실패 (계속 실패 중)");
		assertThat(lastLines()).first().asString().startsWith("[7회째 · 09-24 09:10부터] " + a.getName());

		// 발송한 실행에만 기록
		assertThat(runs.findAll()).filteredOn(run -> run.getFailureNotifiedAt() != null)
			.extracting(TestRun::getStartedAt)
			.containsExactlyInAnyOrder(NINE_TEN.plusSeconds(30), NINE_TEN.plus(Duration.ofMinutes(60)));
		assertThat(every10(70, Set.of("/a"))).isEqualTo(RunResultNotifier.Sent.SUPPRESSED);
	}

	@Test
	void sendsNewFailuresAndRecoveryRightAwayWhileOngoingFailuresAreQuiet() {
		assertThat(every10(0, Set.of("/a"))).isEqualTo(RunResultNotifier.Sent.FAILURE);
		// 계속 실패 중에 다른 케이스가 새로 실패하면 바로
		assertThat(every10(10, Set.of("/a", "/b"))).isEqualTo(RunResultNotifier.Sent.FAILURE);
		assertThat(lastTitle()).endsWith("2건 중 2건 실패 (새로 실패 1건)");
		// 재알림 간격은 마지막 발송부터
		assertThat(every10(20, Set.of("/a", "/b"))).isEqualTo(RunResultNotifier.Sent.SUPPRESSED);
		// 일부 복구는 알리지 않고 다음 재알림에 남은 실패만
		assertThat(every10(30, Set.of("/a"))).isEqualTo(RunResultNotifier.Sent.SUPPRESSED);
		assertThat(every10(70, Set.of("/a"))).isEqualTo(RunResultNotifier.Sent.FAILURE);
		assertThat(lastTitle()).endsWith("2건 중 1건 실패 (계속 실패 중)");
		// 모두 통과하면 바로 복구
		assertThat(every10(80, Set.of())).isEqualTo(RunResultNotifier.Sent.RECOVERY);
		assertThat(notifier.sent()).hasSize(4);
	}

	@Test
	void suppressesOnlyWithinTheSameSuiteAndEnvironment() {
		// staging은 70분 전에 알린 뒤 계속 실패, dev는 방금 알림
		long staging1 = run(Duration.ofMinutes(-70), staging, TestRun.Trigger.SCHEDULED, Set.of("/a"));
		assertThat(runResultNotifier.notifyResult(staging1)).isEqualTo(RunResultNotifier.Sent.FAILURE);
		assertThat(every10(0, Set.of("/a"))).isEqualTo(RunResultNotifier.Sent.FAILURE);

		// dev의 알림과 무관하게 staging은 간격이 지나 다시 알림
		long staging2 = run(Duration.ofMinutes(5), staging, TestRun.Trigger.SCHEDULED, Set.of("/a"));
		assertThat(runResultNotifier.notifyResult(staging2)).isEqualTo(RunResultNotifier.Sent.FAILURE);
		assertThat(lastTitle()).startsWith("[Sentinel] staging ").endsWith("(계속 실패 중)");
	}

	@Test
	void allowsOnePollIntervalOfSlackBeforeReminding() {
		assertThat(every10(0, Set.of("/a"))).isEqualTo(RunResultNotifier.Sent.FAILURE);

		// 간격(1시간)에서 폴링 주기(60초)를 뺀 59분부터 다시 알림
		long early = run(Duration.ofMinutes(59).minusSeconds(1), dev, TestRun.Trigger.SCHEDULED, Set.of("/a"));
		assertThat(runResultNotifier.notifyResult(early)).isEqualTo(RunResultNotifier.Sent.SUPPRESSED);
		long due = run(Duration.ofMinutes(59), dev, TestRun.Trigger.SCHEDULED, Set.of("/a"));
		assertThat(runResultNotifier.notifyResult(due)).isEqualTo(RunResultNotifier.Sent.FAILURE);
	}

	@Test
	void sendsEveryRunWhenTheRepeatIntervalIsZero() {
		RunResultNotifier everyRun = new RunResultNotifier(runs, results, channels,
				new NotificationProperties(null, null, Duration.ofSeconds(3), Duration.ofSeconds(5), Duration.ZERO),
				scheduleProperties, transactions, clock);
		assertThat(everyRun.notifyResult(run(Duration.ZERO, dev, TestRun.Trigger.SCHEDULED, Set.of("/a"))))
			.isEqualTo(RunResultNotifier.Sent.FAILURE);
		assertThat(everyRun.notifyResult(run(Duration.ofMinutes(10), dev, TestRun.Trigger.SCHEDULED, Set.of("/a"))))
			.isEqualTo(RunResultNotifier.Sent.FAILURE);
		// 간격이 없으면 안내 줄도 없음
		assertThat(lastLines()).noneMatch(line -> line.startsWith("계속 실패하면"));
	}

	/** 09:10 + hours 시각의 스케줄 실행 후 알림 */
	@Test
	void saysWhenACaseStillGotNoResponseAfterBeingSentAgain() {
		clock.set(NINE_TEN);
		executor.respondWith(request -> {
			if (request.uri().getPath().equals("/a")) {
				throw new RequestExecutionException(RequestExecutionException.Reason.TIMEOUT, request,
						Duration.ofSeconds(30), null);
			}
			return new HttpExchange(request, 200, Map.of(), "", Duration.ofMillis(5));
		});
		long runId = testRunService.runSuite(suite.getId(), dev.getId(), TestRun.Trigger.SCHEDULED, null);

		assertThat(runResultNotifier.notifyResult(runId)).isEqualTo(RunResultNotifier.Sent.FAILURE);
		assertThat(lastLines()).first().asString().endsWith("(1회 다시 보낸 뒤)");
	}

	/** 09:10 + minutes 시각의 스케줄 실행 후 알림 */
	private RunResultNotifier.Sent every10(int minutes, Set<String> failingPaths) {
		long runId = run(Duration.ofMinutes(minutes), dev, TestRun.Trigger.SCHEDULED, failingPaths);
		return runResultNotifier.notifyResult(runId);
	}

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
