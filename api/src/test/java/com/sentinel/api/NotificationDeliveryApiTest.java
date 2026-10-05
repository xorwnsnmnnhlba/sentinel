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
@SpringBootTest(properties = "sentinel.schedule.enabled=false")
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
	void deletesRecordsOlderThanTheRetention() {
		save(NOW.minus(Duration.ofDays(90)).minusSeconds(1), null, "지난 기록", true, 200, null);
		save(NOW.minus(Duration.ofDays(90)), null, "보관기간 끝", true, 200, null);

		assertThat(service.deleteExpired()).isEqualTo(1);
		assertThat(deliveries.findAll()).extracting(NotificationDelivery::getTitle).containsExactly("보관기간 끝");
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
