package com.sentinel.api;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.sentinel.TestAuthConfiguration;
import com.sentinel.TestcontainersConfiguration;
import com.sentinel.application.FakeRequestExecutorConfiguration;
import com.sentinel.application.MutableClock;
import com.sentinel.application.NotificationDeliveryService;
import com.sentinel.application.TestFixtures;
import com.sentinel.domain.NotificationDelivery;
import com.sentinel.domain.Project;
import com.sentinel.infra.persistence.NotificationDeliveryRepository;
import com.sentinel.infra.persistence.ProjectRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static org.assertj.core.api.Assertions.assertThat;

/** 알림 발송 이력 조회·보관기간 정리 ({@code /api/notification-deliveries}). 발송 경로의 기록은 ProjectWebhookApiTest */
@SpringBootTest(properties = { "sentinel.schedule.enabled=false",
		"sentinel.notification.webhook-url=http://127.0.0.1:9/default-channel" })
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, FakeRequestExecutorConfiguration.class, TestFixtures.class,
		TestAuthConfiguration.class, NotificationDeliveryApiTest.ClockConfiguration.class })
class NotificationDeliveryApiTest {

	private static final Instant NOW = Instant.parse("2026-10-05T00:00:00Z");

	@Autowired
	private MockMvcTester mvc;

	@Autowired
	private TestFixtures fixtures;

	@Autowired
	private NotificationDeliveryRepository deliveries;

	@Autowired
	private NotificationDeliveryService service;

	@Autowired
	private ProjectRepository projects;

	@Autowired
	private MutableClock clock;

	private Project billing;

	@BeforeEach
	void setUp() {
		clock.set(NOW);
		billing = fixtures.project();
	}

	@AfterEach
	void cleanUp() {
		fixtures.deleteAll();
	}

	@Test
	void listsNewestFirstAndFiltersByProjectAndFailure() {
		Project other = fixtures.project();
		save(NOW.minusSeconds(30), null, "주간 요약", true, 200, null);
		save(NOW.minusSeconds(20), billing.getId(), "billing 실패", false, 500, null);
		save(NOW.minusSeconds(10), other.getId(), "other 실패", false, null, "TIMEOUT");

		var all = assertThat(mvc.get().uri("/api/notification-deliveries")).hasStatusOk().bodyJson();
		all.extractingPath("$.content[*].title").isEqualTo(List.of("other 실패", "billing 실패", "주간 요약"));
		all.extractingPath("$.totalElements").isEqualTo(3);
		all.extractingPath("$.content[0].sentAt").isEqualTo("2026-10-04T23:59:50Z");
		all.extractingPath("$.content[0].error").isEqualTo("TIMEOUT");
		all.extractingPath("$.content[2].project").isNull();
		all.extractingPath("$.content[2].channel").isEqualTo("DEFAULT");

		assertThat(mvc.get().uri("/api/notification-deliveries?projectId={id}", billing.getId())).bodyJson()
			.extractingPath("$.content[*].title")
			.isEqualTo(List.of("billing 실패"));
		assertThat(mvc.get().uri("/api/notification-deliveries?failed=true")).bodyJson()
			.extractingPath("$.content[*].title")
			.isEqualTo(List.of("other 실패", "billing 실패"));
		assertThat(mvc.get().uri("/api/notification-deliveries?projectId={id}&failed=true", other.getId()))
			.bodyJson()
			.extractingPath("$.content[*].title")
			.isEqualTo(List.of("other 실패"));
	}

	@Test
	void pagesAndKeepsTheNameOfDeletedProjects() {
		for (int i = 0; i < 3; i++) {
			save(NOW.minusSeconds(i), billing.getId(), "알림 " + i, true, 200, null);
		}
		mvc.delete().uri("/api/projects/{id}", billing.getId()).exchange();

		var page = assertThat(mvc.get().uri("/api/notification-deliveries?page=1&size=2")).bodyJson();
		page.extractingPath("$.content[*].title").isEqualTo(List.of("알림 2"));
		page.extractingPath("$.totalPages").isEqualTo(2);
		page.extractingPath("$.content[0].project.name").isEqualTo(billing.getName());
		// 크기는 1~100으로 보정
		assertThat(mvc.get().uri("/api/notification-deliveries?size=0")).bodyJson()
			.extractingPath("$.size")
			.isEqualTo(1);
		assertThat(mvc.get().uri("/api/notification-deliveries?size=1000")).bodyJson()
			.extractingPath("$.size")
			.isEqualTo(100);
	}

	@Test
	void reportsChannelsWhoseLastDeliveryFailedUntilTheyDeliverAgain() {
		withWebhook(billing);
		Project recovered = withWebhook(fixtures.project());
		Project removed = fixtures.project();
		Project archived = withWebhook(fixtures.project());
		Project stale = withWebhook(fixtures.project());
		Instant hour = NOW.minus(Duration.ofHours(1));
		save(hour.minus(Duration.ofHours(2)), billing.getId(), "통과", true, 200, null);
		save(hour.minus(Duration.ofHours(1)), billing.getId(), "실패 1", false, 404, null);
		save(hour, billing.getId(), "실패 2", false, 404, null);
		save(hour.minus(Duration.ofHours(1)), recovered.getId(), "실패", false, 500, null);
		save(hour, recovered.getId(), "복구", true, 200, null);
		// 기본 채널: 실패 뒤의 로그 기록은 채널 상태와 무관
		save(NOW.minus(Duration.ofMinutes(30)), null, "기본 실패", false, null, "TIMEOUT");
		saveLog(NOW.minus(Duration.ofMinutes(10)));
		// 지금 쓰지 않는 채널, 7일보다 오래된 실패
		save(hour, removed.getId(), "Webhook 지운 프로젝트", false, 404, null);
		save(hour, archived.getId(), "삭제된 프로젝트", false, 404, null);
		mvc.delete().uri("/api/projects/{id}", archived.getId()).exchange();
		save(NOW.minus(Duration.ofDays(7)).minusSeconds(1), stale.getId(), "오래된 실패", false, 404, null);

		var health = assertThat(mvc.get().uri("/api/notification-channel-health")).hasStatusOk().bodyJson();
		health.extractingPath("$.defaultChannelConfigured").isEqualTo(true);
		// 오래 실패한 채널 먼저
		health.extractingPath("$.failing[*].channel").isEqualTo(List.of("PROJECT", "DEFAULT"));
		health.extractingPath("$.failing[0].project.name").isEqualTo(billing.getName());
		health.extractingPath("$.failing[0].failingSince").isEqualTo("2026-10-04T22:00:00Z");
		health.extractingPath("$.failing[0].lastSentAt").isEqualTo("2026-10-04T23:00:00Z");
		health.extractingPath("$.failing[0].failures").isEqualTo(2);
		health.extractingPath("$.failing[0].statusCode").isEqualTo(404);
		health.extractingPath("$.failing[0].format").isEqualTo("TEXT");
		health.extractingPath("$.failing[1].project").isNull();
		health.extractingPath("$.failing[1].error").isEqualTo("TIMEOUT");
		health.extractingPath("$.failing[1].statusCode").isNull();
		health.extractingPath("$.failing[1].failures").isEqualTo(1);

		// 한 번 보내면 빠짐
		save(NOW, billing.getId(), "다시 통과", true, 200, null);
		assertThat(mvc.get().uri("/api/notification-channel-health")).bodyJson()
			.extractingPath("$.failing[*].channel")
			.isEqualTo(List.of("DEFAULT"));
	}

	@Test
	void judgesOnlyTheLastSevenDays() {
		withWebhook(billing);
		save(NOW.minus(Duration.ofDays(7)), billing.getId(), "7일 전 실패", false, 500, null);

		assertThat(mvc.get().uri("/api/notification-channel-health")).bodyJson()
			.extractingPath("$.failing[0].failingSince")
			.isEqualTo("2026-09-28T00:00:00Z");
	}

	@Test
	void deletesRecordsOlderThanTheRetention() {
		save(NOW.minus(Duration.ofDays(90)).minusSeconds(1), null, "지난 기록", true, 200, null);
		save(NOW.minus(Duration.ofDays(90)), null, "보관기간 끝", true, 200, null);

		assertThat(service.deleteExpired()).isEqualTo(1);
		assertThat(deliveries.findAll()).extracting(NotificationDelivery::getTitle).containsExactly("보관기간 끝");
	}

	private Project withWebhook(Project project) {
		Project stored = projects.findById(project.getId()).orElseThrow();
		stored.changeWebhook("cipher-text");
		return projects.save(stored);
	}

	private void saveLog(Instant sentAt) {
		deliveries.save(new NotificationDelivery(sentAt, NotificationDelivery.Channel.DEFAULT, null,
				NotificationDelivery.Format.LOG, "로그 기록", NotificationDelivery.Tone.INFO, true, null, null));
	}

	private void save(Instant sentAt, Long projectId, String title, boolean delivered, Integer statusCode,
			String error) {
		deliveries.save(new NotificationDelivery(sentAt,
				projectId == null ? NotificationDelivery.Channel.DEFAULT : NotificationDelivery.Channel.PROJECT,
				projectId, NotificationDelivery.Format.TEXT, title, NotificationDelivery.Tone.FAILURE, delivered,
				statusCode, error));
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
