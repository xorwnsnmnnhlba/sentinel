package com.sentinel.application.notification;

import java.time.Instant;
import java.util.List;

import com.sentinel.TestcontainersConfiguration;
import com.sentinel.application.FakeRequestExecutorConfiguration;
import com.sentinel.application.MutableClock;
import com.sentinel.application.TestFixtures;
import com.sentinel.domain.NotificationDelivery;
import com.sentinel.infra.persistence.NotificationDeliveryRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** 알림 발송 이력 저장. 저장 실패는 발송한 쪽으로 전파하지 않음 */
@SpringBootTest(properties = "sentinel.schedule.enabled=false")
@ExtendWith(OutputCaptureExtension.class)
@Import({ TestcontainersConfiguration.class, FakeRequestExecutorConfiguration.class, TestFixtures.class,
		NotificationDeliveryRecorderTest.ClockConfiguration.class })
class NotificationDeliveryRecorderTest {

	private static final Instant NOW = Instant.parse("2026-10-05T00:00:00Z");

	@Autowired
	private NotificationDeliveryRecorder recorder;

	@Autowired
	private NotificationDeliveryRepository deliveries;

	@Autowired
	private TestFixtures fixtures;

	@AfterEach
	void cleanUp() {
		fixtures.deleteAll();
	}

	@Test
	void savesTheTitleAndResultWithTheSendingTime() {
		recorder.record(DeliveryLog.Target.defaultWebhook("https://hooks.slack.com/services/T0/B0/secret"),
				new Notification("[Sentinel] dev smoke - 실패", List.of("GET /a → 500"), Notification.Tone.FAILURE,
						List.of()),
				new WebhookSender.Delivery(false, null, "TIMEOUT"));

		assertThat(deliveries.findAll()).singleElement().satisfies(delivery -> {
			assertThat(delivery.getSentAt()).isEqualTo(NOW);
			assertThat(delivery.getChannel()).isEqualTo(NotificationDelivery.Channel.DEFAULT);
			assertThat(delivery.getProjectId()).isNull();
			assertThat(delivery.getFormat()).isEqualTo(NotificationDelivery.Format.SLACK);
			assertThat(delivery.getTitle()).isEqualTo("[Sentinel] dev smoke - 실패");
			assertThat(delivery.getTone()).isEqualTo(NotificationDelivery.Tone.FAILURE);
			assertThat(delivery.isDelivered()).isFalse();
			assertThat(delivery.getStatusCode()).isNull();
			assertThat(delivery.getError()).isEqualTo("TIMEOUT");
		});
	}

	@Test
	void swallowsRecordingFailures(CapturedOutput output) {
		// 저장할 수 없는 기록 (프로젝트 채널인데 프로젝트 없음)
		DeliveryLog.Target broken = new DeliveryLog.Target(NotificationDelivery.Channel.PROJECT, null,
				NotificationDelivery.Format.TEXT);

		assertThatCode(() -> recorder.record(broken, new Notification("알림", List.of()),
				new WebhookSender.Delivery(true, 200, null)))
			.doesNotThrowAnyException();
		assertThat(deliveries.count()).isZero();
		assertThat(output).contains("Failed to record notification delivery: 알림 (IllegalArgumentException)");
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class ClockConfiguration {

		@Bean
		@Primary
		MutableClock mutableClock() {
			return new MutableClock(NOW);
		}

	}

}
