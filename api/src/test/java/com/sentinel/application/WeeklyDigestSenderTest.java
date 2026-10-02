package com.sentinel.application;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;

import com.sentinel.TestcontainersConfiguration;
import com.sentinel.application.notification.Notification;
import com.sentinel.application.notification.RecordingNotifier;
import com.sentinel.domain.DigestDelivery;
import com.sentinel.infra.persistence.DigestDeliveryRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.support.CronExpression;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 주간 요약 발송 시점과 중복 방지. 자동 확인을 끄고 {@link WeeklyDigestSender#sendIfDue()}를 직접 호출하여 시각 제어.
 * 다른 테스트 컨텍스트의 자동 확인(실제 시각)과 겹치지 않도록 2026-08월 날짜 사용
 */
@SpringBootTest(properties = { "sentinel.schedule.enabled=false",
		"sentinel.notification.web-base-url=http://sentinel.test" })
@Import({ TestcontainersConfiguration.class, FakeRequestExecutorConfiguration.class, TestFixtures.class,
		WeeklyDigestSenderTest.ClockConfiguration.class })
class WeeklyDigestSenderTest {

	/** 2026-08-17 (월) 09:00 KST */
	private static final Instant MONDAY_NINE = Instant.parse("2026-08-17T00:00:00Z");

	@Autowired
	private WeeklyDigestSender sender;

	@Autowired
	private DigestDeliveryRepository deliveries;

	@Autowired
	private MutableClock clock;

	@Autowired
	private RecordingNotifier notifier;

	@Autowired
	private TestFixtures fixtures;

	@Autowired
	private DigestProperties properties;

	@Autowired
	private ScheduleProperties scheduleProperties;

	@Autowired
	private WeeklyDigestService digests;

	@Autowired
	private Transactions transactions;

	@BeforeEach
	void setUp() {
		deliveries.deleteAllInBatch();
		// 앞 주 요약은 이미 보냄
		deliveries.save(new DigestDelivery(MONDAY_NINE.minus(Duration.ofDays(7)), LocalDate.of(2026, 8, 3),
				LocalDate.of(2026, 8, 9)));
	}

	@AfterEach
	void cleanUp() {
		deliveries.deleteAllInBatch();
		fixtures.deleteAll();
		notifier.reset();
	}

	@Test
	void sendsOnceWhenTheScheduledTimeComes() {
		clock.set(MONDAY_NINE.minusSeconds(60));
		assertThat(sender.sendIfDue()).isFalse();

		clock.set(MONDAY_NINE.plusSeconds(30));
		assertThat(sender.sendIfDue()).isTrue();
		clock.set(MONDAY_NINE.plusSeconds(90));
		assertThat(sender.sendIfDue()).isFalse();

		assertThat(notifier.sent()).singleElement().satisfies(digest -> {
			// 발송일 전날까지 7일
			assertThat(digest.title()).isEqualTo("[Sentinel] 주간 요약 08-10 ~ 08-16");
			assertThat(digest.lines()).last().isEqualTo("대시보드: http://sentinel.test/");
		});
		assertThat(deliveries.findAll()).filteredOn(delivery -> delivery.getScheduledAt().equals(MONDAY_NINE))
			.singleElement()
			.satisfies(delivery -> {
				assertThat(delivery.getPeriodFrom()).isEqualTo("2026-08-10");
				assertThat(delivery.getPeriodTo()).isEqualTo("2026-08-16");
			});
	}

	@Test
	void sendsLateOnceAfterBeingOffAtTheScheduledTime() {
		// 월요일 내내 꺼져 있다가 수요일 10:00에 켜짐
		clock.set(Instant.parse("2026-08-19T01:00:00Z"));

		assertThat(sender.sendIfDue()).isTrue();
		assertThat(sender.sendIfDue()).isFalse();

		assertThat(notifier.sent()).extracting(Notification::title).containsExactly("[Sentinel] 주간 요약 08-10 ~ 08-16");
	}

	@Test
	void sendsOnlyTheLatestDigestAfterBeingOffForWeeks() {
		// 3주 꺼져 있다가 2026-09-01 (화) 10:00에 켜짐. 08-17·08-24는 건너뛰고 08-31 예정분만
		clock.set(Instant.parse("2026-09-01T01:00:00Z"));

		assertThat(sender.sendIfDue()).isTrue();

		assertThat(notifier.sent()).extracting(Notification::title).containsExactly("[Sentinel] 주간 요약 08-24 ~ 08-30");
	}

	@Test
	void doesNotResendAfterRestartOrWhenTheNotificationFailed() {
		WeeklyDigestSender failing = new WeeklyDigestSender(properties, scheduleProperties, digests, deliveries,
				notification -> {
					throw new IllegalStateException("webhook down");
				}, transactions, clock);
		clock.set(MONDAY_NINE.plusSeconds(30));

		// 발송 실패해도 기록이 남음
		assertThat(failing.sendIfDue()).isTrue();

		// 재시작(새 인스턴스)해도 DB 기록으로 판단
		assertThat(sender.sendIfDue()).isFalse();
		assertThat(notifier.sent()).isEmpty();
	}

	@Test
	void findsTheLatestOccurrenceWithinTheLookback() {
		CronExpression monday = CronExpression.parse("0 0 9 * * MON");

		assertThat(sender.latestOccurrence(monday, MONDAY_NINE)).isEqualTo(zoned("2026-08-17T09:00"));
		assertThat(sender.latestOccurrence(monday, MONDAY_NINE.minusNanos(1))).isEqualTo(zoned("2026-08-10T09:00"));
		// 7일 안에 예정 시각이 없으면 없음 (정확히 7일 전은 제외)
		CronExpression newYear = CronExpression.parse("0 0 9 1 1 *");
		assertThat(sender.latestOccurrence(newYear, Instant.parse("2026-01-07T23:59:59Z")))
			.isEqualTo(zoned("2026-01-01T09:00"));
		assertThat(sender.latestOccurrence(newYear, Instant.parse("2026-01-08T00:00:00Z"))).isNull();
	}

	private ZonedDateTime zoned(String local) {
		return LocalDateTime.parse(local).atZone(scheduleProperties.zone());
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class ClockConfiguration {

		@Bean
		@Primary
		MutableClock mutableClock() {
			return new MutableClock(MONDAY_NINE);
		}

		@Bean
		@Primary
		RecordingNotifier recordingNotifier() {
			return new RecordingNotifier();
		}

	}

}
