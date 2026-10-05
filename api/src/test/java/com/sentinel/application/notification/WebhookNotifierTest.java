package com.sentinel.application.notification;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sentinel.domain.NotificationDelivery;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 수신 서버는 JDK 내장 HTTP 서버로 대체. 요청 1건만 확인하면 되므로 WireMock 컨테이너 없이 실행
 */
@ExtendWith(OutputCaptureExtension.class)
class WebhookNotifierTest {

	private HttpServer server;

	private final List<String> bodies = new CopyOnWriteArrayList<>();

	private final List<String> contentTypes = new CopyOnWriteArrayList<>();

	private volatile int responseStatus = 200;

	private volatile long responseDelayMillis = 0;

	/** 발송 이력에 넘긴 결과 */
	private final List<WebhookSender.Delivery> deliveries = new CopyOnWriteArrayList<>();

	private final List<DeliveryLog.Target> targets = new CopyOnWriteArrayList<>();

	private final DeliveryLog recorded = (target, notification, delivery) -> {
		targets.add(target);
		deliveries.add(delivery);
	};

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/hook", exchange -> {
			try (InputStream body = exchange.getRequestBody()) {
				bodies.add(new String(body.readAllBytes(), StandardCharsets.UTF_8));
			}
			contentTypes.add(String.valueOf(exchange.getRequestHeaders().getFirst("Content-Type")));
			sleep(responseDelayMillis);
			exchange.sendResponseHeaders(responseStatus, -1);
			exchange.close();
		});
		server.start();
	}

	@AfterEach
	void stopServer() {
		server.stop(0);
	}

	@Test
	void postsTitleAndLinesAsOneTextField() {
		notifier().send(new Notification("[Sentinel] dev smoke - 4건 중 2건 실패", List.of("GET /a → 500", "GET /b → timeout")));

		assertThat(bodies).hasSize(1);
		assertThat(bodies.getFirst()).isEqualTo(
				"{\"text\":\"[Sentinel] dev smoke - 4건 중 2건 실패\\nGET /a → 500\\nGET /b → timeout\"}");
		assertThat(contentTypes.getFirst()).startsWith("application/json");
		assertThat(deliveries).containsExactly(new WebhookSender.Delivery(true, 200, null));
		assertThat(targets).containsExactly(
				new DeliveryLog.Target(NotificationDelivery.Channel.DEFAULT, null, NotificationDelivery.Format.TEXT));
	}

	@Test
	void recordsTheStatusTheChannelAnswered() {
		// Teams 워크플로 등은 200이 아닌 2xx로 응답
		responseStatus = 204;

		notifier().send(new Notification("알림", List.of()));

		assertThat(deliveries).containsExactly(new WebhookSender.Delivery(true, 204, null));
	}

	@Test
	void keepsRunningWhenTheChannelReturnsAnError() {
		responseStatus = 500;

		assertThatCode(() -> notifier().send(new Notification("알림", List.of()))).doesNotThrowAnyException();
		assertThat(bodies).hasSize(1);
		assertThat(deliveries).containsExactly(new WebhookSender.Delivery(false, 500, null));
	}

	@Test
	void keepsRunningWhenTheChannelIsSlow(CapturedOutput output) {
		responseDelayMillis = 500;
		WebhookNotifier notifier = new WebhookNotifier(url(), Duration.ofSeconds(2), Duration.ofMillis(100), recorded);

		assertThatCode(() -> notifier.send(new Notification("알림", List.of()))).doesNotThrowAnyException();
		assertThat(output).contains("Notification delivery failed: 알림 (timeout)");
		assertThat(deliveries).containsExactly(new WebhookSender.Delivery(false, null, "TIMEOUT"));
	}

	@Test
	void keepsRunningWhenTheChannelIsUnreachable() {
		WebhookNotifier notifier = new WebhookNotifier("http://127.0.0.1:%d/hook".formatted(closedPort()),
				Duration.ofMillis(500), Duration.ofMillis(500), recorded);

		assertThatCode(() -> notifier.send(new Notification("알림", List.of()))).doesNotThrowAnyException();
		assertThat(deliveries).containsExactly(new WebhookSender.Delivery(false, null, "CONNECTION_FAILED"));
	}

	@Test
	void recordsLoggedNotificationsAsWrittenToTheLog() {
		new LoggingNotifier(recorded).send(new Notification("알림", List.of()));

		assertThat(targets).containsExactly(
				new DeliveryLog.Target(NotificationDelivery.Channel.DEFAULT, null, NotificationDelivery.Format.LOG));
		assertThat(deliveries).containsExactly(new WebhookSender.Delivery(true, null, null));
	}

	@Test
	void namesTheChannelFormatFromTheAddress() {
		assertThat(DeliveryLog.Target.defaultWebhook("https://hooks.slack.com/services/T0/B0/x").format())
			.isEqualTo(NotificationDelivery.Format.SLACK);
		assertThat(DeliveryLog.Target.project(7, "https://example.webhook.office.com/x"))
			.isEqualTo(new DeliveryLog.Target(NotificationDelivery.Channel.PROJECT, 7L, NotificationDelivery.Format.TEAMS));
	}

	@Test
	void leavesTheAddressOutOfTheLog(CapturedOutput output) {
		// 주소는 자격증명. 연결 실패 예외 메시지에는 요청 주소가 들어 있으므로 그대로 남기지 않음
		String secretPath = "/hook/T000-secret-token";
		new WebhookNotifier("http://127.0.0.1:%d%s".formatted(closedPort(), secretPath), Duration.ofMillis(500),
				Duration.ofMillis(500), recorded)
			.send(new Notification("알림", List.of()));
		responseStatus = 404;
		new WebhookNotifier(url() + "?token=secret-token", Duration.ofSeconds(2), Duration.ofSeconds(2), recorded)
			.send(new Notification("알림", List.of()));

		assertThat(output).contains("Notification delivery failed: 알림 (connection failed)")
			.contains("Notification delivery failed: 알림 (HTTP 404)")
			.doesNotContain("secret-token")
			.doesNotContain("127.0.0.1");
	}

	@Test
	void buildsTheRunLinkOnlyWhenTheWebAddressIsSet() {
		assertThat(properties(null).runUrl(12)).isNull();
		assertThat(webProperties("").runUrl(12)).isNull();
		assertThat(webProperties("http://sentinel.example.com").runUrl(12))
			.isEqualTo("http://sentinel.example.com/runs/12");
		// 끝의 / 는 제거하여 //runs/12 방지
		assertThat(webProperties("http://sentinel.example.com//").runUrl(12))
			.isEqualTo("http://sentinel.example.com/runs/12");
	}

	@Test
	void usesTheLogWhenNoWebhookIsConfigured() {
		NotificationConfig config = new NotificationConfig();

		assertThat(config.notifier(properties(null), recorded)).isInstanceOf(LoggingNotifier.class);
		assertThat(config.notifier(properties("  "), recorded)).isInstanceOf(LoggingNotifier.class);
		assertThat(config.notifier(properties(url()), recorded)).isInstanceOf(WebhookNotifier.class);
	}

	private static NotificationProperties webProperties(String webBaseUrl) {
		return new NotificationProperties(null, webBaseUrl, Duration.ofSeconds(3), Duration.ofSeconds(5), Duration.ofHours(1));
	}

	private static NotificationProperties properties(String webhookUrl) {
		return new NotificationProperties(webhookUrl, null, Duration.ofSeconds(3), Duration.ofSeconds(5), Duration.ofHours(1));
	}

	private WebhookNotifier notifier() {
		return new WebhookNotifier(url(), Duration.ofSeconds(2), Duration.ofSeconds(2), recorded);
	}

	private String url() {
		return "http://127.0.0.1:%d/hook".formatted(server.getAddress().getPort());
	}

	private static int closedPort() {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static void sleep(long millis) {
		if (millis <= 0) {
			return;
		}
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

}
