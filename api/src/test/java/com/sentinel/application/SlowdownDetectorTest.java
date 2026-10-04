package com.sentinel.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import com.sentinel.TestcontainersConfiguration;
import com.sentinel.application.notification.Notification;
import com.sentinel.application.notification.RecordingNotifier;
import com.sentinel.domain.Environment;
import com.sentinel.domain.Project;
import com.sentinel.domain.TestCase;
import com.sentinel.domain.TestRun;
import com.sentinel.domain.TestSuite;
import com.sentinel.infra.persistence.SlowdownAlertRepository;
import com.sentinel.infra.persistence.TestCaseRepository;
import com.sentinel.runner.HttpMethod;
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
 * 응답시간 악화·회복 알림. 실행 시각과 응답시간을 지정해 결과를 만들고 확인 시각을 옮겨 가며 확인 (기준: 24시간 vs 그 전 7일,
 * 50% 이상이면서 100ms 이상, 기간별 5건 이상)
 */
@SpringBootTest(properties = { "sentinel.schedule.enabled=false",
		"sentinel.notification.web-base-url=http://sentinel.test" })
@Import({ TestcontainersConfiguration.class, FakeRequestExecutorConfiguration.class, TestFixtures.class,
		SlowdownDetectorTest.TestBeans.class })
class SlowdownDetectorTest {

	/** 확인 시각 2026-08-10 09:00 KST */
	private static final Instant NOW = Instant.parse("2026-08-10T00:00:00Z");

	@Autowired
	private SlowdownDetector detector;

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
	private SlowdownAlertRepository alerts;

	@Autowired
	private TestCaseRepository testCases;

	@Autowired
	private Transactions transactions;

	private Project project;

	private TestSuite suite;

	private Environment dev;

	private TestCase payment;

	@BeforeEach
	void setUp() {
		alerts.deleteAllInBatch();
		project = fixtures.project();
		dev = fixtures.environment(project, "dev", Map.of());
		suite = fixtures.suite(project);
		payment = fixtures.testCase(suite, HttpMethod.GET, "/pay", null, null);
	}

	@AfterEach
	void cleanUp() {
		alerts.deleteAllInBatch();
		fixtures.deleteAll();
		executor.reset();
		notifier.reset();
	}

	@Test
	void alertsOnceWhenSlowAndOnceWhenRecovered() {
		// 그 전 7일 200ms 6건, 최근 24시간 350ms 5건(최소 표본) (1.75배, +150ms)
		runs(suite, NOW.minus(Duration.ofDays(3)), 6, 200);
		runs(suite, NOW.minus(Duration.ofHours(7)), 5, 350);
		clock.set(NOW);

		assertThat(detector.check()).isEqualTo(new SlowdownDetector.Outcome(1, 0));

		assertThat(notifier.sent()).singleElement().satisfies(notification -> {
			assertThat(notification.title()).isEqualTo("[Sentinel] " + project.getName() + " - 응답시간 악화 1건");
			assertThat(notification.lines()).containsExactly(
					suite.getName() + " · " + payment.getName() + " (dev) p95 200ms → 350ms (+75%, 최근 응답 5건)",
					"대시보드: http://sentinel.test/");
		});

		// 느린 동안은 다시 알리지 않음
		clock.set(NOW.plus(Duration.ofHours(1)));
		assertThat(detector.check()).isEqualTo(new SlowdownDetector.Outcome(0, 0));
		assertThat(notifier.sent()).hasSize(1);

		// 다음 날 220ms로 돌아옴. 비교 기간에 느린 값이 섞여도 감지 당시 기준(200ms)으로 판단
		runs(suite, NOW.plus(Duration.ofHours(18)), 6, 220);
		clock.set(NOW.plus(Duration.ofHours(25)));

		assertThat(detector.check()).isEqualTo(new SlowdownDetector.Outcome(0, 1));
		assertThat(notifier.sent().getLast()).satisfies(notification -> {
			assertThat(notification.title()).isEqualTo("[Sentinel] " + project.getName() + " - 응답시간 회복 1건");
			assertThat(notification.lines()).first()
				.isEqualTo(suite.getName() + " · " + payment.getName() + " (dev) p95 220ms로 회복 (악화 당시 350ms, 기준 200ms)");
		});
		assertThat(alerts.findByResolvedAtIsNull()).isEmpty();
	}

	@Test
	void staysOpenWhileStillSlowEvenAfterTheBaselineCatchesUp() {
		runs(suite, NOW.minus(Duration.ofDays(3)), 6, 200);
		runs(suite, NOW.minus(Duration.ofHours(7)), 6, 350);
		clock.set(NOW);
		detector.check();

		// 9일 동안 계속 350ms (4시간 간격). 비교 기간도 350ms가 되어 지금 기준으로는 차이가 없지만, 감지 당시 기준(200ms)으로는 여전히 느림
		runs(suite, dev, NOW.plus(Duration.ofHours(1)), 54, 350, Duration.ofHours(4));
		clock.set(NOW.plus(Duration.ofDays(9)));

		assertThat(detector.check()).isEqualTo(new SlowdownDetector.Outcome(0, 0));
		assertThat(alerts.findByResolvedAtIsNull()).hasSize(1);
		assertThat(notifier.sent()).hasSize(1);
	}

	@Test
	void ignoresSmallIncreasesAndTooFewSamples() {
		TestSuite fast = fixtures.suite(project);
		fixtures.testCase(fast, HttpMethod.GET, "/fast", null, null);
		TestSuite sparse = fixtures.suite(project);
		fixtures.testCase(sparse, HttpMethod.GET, "/sparse", null, null);
		// 3배지만 +40ms
		runs(fast, NOW.minus(Duration.ofDays(3)), 6, 20);
		runs(fast, NOW.minus(Duration.ofHours(7)), 6, 60);
		// 기준을 넘지만 최근 응답 4건
		runs(sparse, NOW.minus(Duration.ofDays(3)), 6, 200);
		runs(sparse, NOW.minus(Duration.ofHours(7)), 4, 400);
		clock.set(NOW);

		assertThat(detector.check()).isEqualTo(new SlowdownDetector.Outcome(0, 0));
		assertThat(notifier.sent()).isEmpty();
	}

	@Test
	void keepsTheAlertOpenWithoutRecentResponsesAndClosesItQuietlyWhenTheCaseIsDeleted() {
		runs(suite, NOW.minus(Duration.ofDays(3)), 6, 200);
		runs(suite, NOW.minus(Duration.ofHours(7)), 6, 350);
		clock.set(NOW);
		detector.check();

		// 스케줄이 멈춰 최근 24시간 응답이 없으면 판단하지 않고 열어 둠
		clock.set(NOW.plus(Duration.ofDays(2)));
		assertThat(detector.check()).isEqualTo(new SlowdownDetector.Outcome(0, 0));
		assertThat(alerts.findByResolvedAtIsNull()).hasSize(1);

		transactions.writeWithoutResult(
				status -> testCases.findById(payment.getId()).orElseThrow().archive(clock.instant()));
		assertThat(detector.check()).isEqualTo(new SlowdownDetector.Outcome(0, 0));
		assertThat(alerts.findByResolvedAtIsNull()).isEmpty();
		assertThat(notifier.sent()).extracting(Notification::title).allMatch(title -> title.contains("응답시간 악화"));
	}

	@Test
	void sendsOneNotificationPerProject() {
		Project other = fixtures.project();
		Environment otherDev = fixtures.environment(other, "dev", Map.of());
		TestSuite otherSuite = fixtures.suite(other);
		fixtures.testCase(otherSuite, HttpMethod.GET, "/other", null, null);
		TestCase refund = fixtures.testCase(suite, HttpMethod.GET, "/refund", null, null);
		runs(suite, NOW.minus(Duration.ofDays(3)), 6, 200);
		runs(suite, NOW.minus(Duration.ofHours(7)), 6, 500);
		runs(otherSuite, otherDev, NOW.minus(Duration.ofDays(3)), 6, 200);
		runs(otherSuite, otherDev, NOW.minus(Duration.ofHours(7)), 6, 400);
		clock.set(NOW);

		assertThat(detector.check()).isEqualTo(new SlowdownDetector.Outcome(3, 0));

		assertThat(notifier.sent()).hasSize(2);
		assertThat(notifier.sent()).extracting(Notification::title)
			.contains("[Sentinel] " + project.getName() + " - 응답시간 악화 2건",
					"[Sentinel] " + other.getName() + " - 응답시간 악화 1건");
		assertThat(notifier.sent()).filteredOn(n -> n.title().contains(project.getName()))
			.singleElement()
			.satisfies(n -> assertThat(n.lines()).anyMatch(line -> line.contains(refund.getName())));
	}

	/** {@code from}부터 1시간 간격으로 {@code count}회 스위트 실행, 모든 케이스가 {@code millis}에 응답 */
	private void runs(TestSuite target, Instant from, int count, long millis) {
		runs(target, dev, from, count, millis);
	}

	private void runs(TestSuite target, Environment environment, Instant from, int count, long millis) {
		runs(target, environment, from, count, millis, Duration.ofHours(1));
	}

	private void runs(TestSuite target, Environment environment, Instant from, int count, long millis, Duration every) {
		executor.respondIn(request -> Duration.ofMillis(millis));
		for (int i = 0; i < count; i++) {
			clock.set(from.plus(every.multipliedBy(i)));
			testRunService.runSuite(target.getId(), environment.getId(), TestRun.Trigger.SCHEDULED, null);
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class TestBeans {

		@Bean
		@Primary
		MutableClock mutableClock() {
			return new MutableClock(NOW);
		}

		@Bean
		@Primary
		RecordingNotifier recordingNotifier() {
			return new RecordingNotifier();
		}

	}

}
