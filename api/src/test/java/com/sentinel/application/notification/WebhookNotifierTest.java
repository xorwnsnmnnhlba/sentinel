package com.sentinel.application.notification;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

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
	}

	@Test
	void keepsRunningWhenTheChannelReturnsAnError() {
		responseStatus = 500;

		assertThatCode(() -> notifier().send(new Notification("알림", List.of()))).doesNotThrowAnyException();
		assertThat(bodies).hasSize(1);
	}

	@Test
	void keepsRunningWhenTheChannelIsSlow(CapturedOutput output) {
		responseDelayMillis = 500;
		WebhookNotifier notifier = new WebhookNotifier(url(), Duration.ofSeconds(2), Duration.ofMillis(100));

		assertThatCode(() -> notifier.send(new Notification("알림", List.of()))).doesNotThrowAnyException();
		assertThat(output).contains("Notification delivery failed: 알림 (timeout)");
	}

	@Test
	void keepsRunningWhenTheChannelIsUnreachable() {
		WebhookNotifier notifier = new WebhookNotifier("http://127.0.0.1:%d/hook".formatted(closedPort()),
				Duration.ofMillis(500), Duration.ofMillis(500));

		assertThatCode(() -> notifier.send(new Notification("알림", List.of()))).doesNotThrowAnyException();
	}

	@Test
	void leavesTheAddressOutOfTheLog(CapturedOutput output) {
		// 주소는 자격증명. 연결 실패 예외 메시지에는 요청 주소가 들어 있으므로 그대로 남기지 않음
		String secretPath = "/hook/T000-secret-token";
		new WebhookNotifier("http://127.0.0.1:%d%s".formatted(closedPort(), secretPath), Duration.ofMillis(500),
				Duration.ofMillis(500))
			.send(new Notification("알림", List.of()));
		responseStatus = 404;
		new WebhookNotifier(url() + "?token=secret-token", Duration.ofSeconds(2), Duration.ofSeconds(2))
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

		assertThat(config.notifier(properties(null))).isInstanceOf(LoggingNotifier.class);
		assertThat(config.notifier(properties("  "))).isInstanceOf(LoggingNotifier.class);
		assertThat(config.notifier(properties(url()))).isInstanceOf(WebhookNotifier.class);
	}

	private static NotificationProperties webProperties(String webBaseUrl) {
		return new NotificationProperties(null, webBaseUrl, Duration.ofSeconds(3), Duration.ofSeconds(5), Duration.ofHours(1));
	}

	private static NotificationProperties properties(String webhookUrl) {
		return new NotificationProperties(webhookUrl, null, Duration.ofSeconds(3), Duration.ofSeconds(5), Duration.ofHours(1));
	}

	private WebhookNotifier notifier() {
		return new WebhookNotifier(url(), Duration.ofSeconds(2), Duration.ofSeconds(2));
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
