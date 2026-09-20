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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 수신 서버는 JDK 내장 HTTP 서버로 대체. 요청 1건만 확인하면 되므로 WireMock 컨테이너 없이 실행
 */
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
	void keepsRunningWhenTheChannelIsSlow() {
		responseDelayMillis = 500;
		WebhookNotifier notifier = new WebhookNotifier(url(), Duration.ofSeconds(2), Duration.ofMillis(100));

		assertThatCode(() -> notifier.send(new Notification("알림", List.of()))).doesNotThrowAnyException();
	}

	@Test
	void keepsRunningWhenTheChannelIsUnreachable() {
		WebhookNotifier notifier = new WebhookNotifier("http://127.0.0.1:%d/hook".formatted(closedPort()),
				Duration.ofMillis(500), Duration.ofMillis(500));

		assertThatCode(() -> notifier.send(new Notification("알림", List.of()))).doesNotThrowAnyException();
	}

	@Test
	void usesTheLogWhenNoWebhookIsConfigured() {
		NotificationConfig config = new NotificationConfig();

		assertThat(config.notifier(properties(null))).isInstanceOf(LoggingNotifier.class);
		assertThat(config.notifier(properties("  "))).isInstanceOf(LoggingNotifier.class);
		assertThat(config.notifier(properties(url()))).isInstanceOf(WebhookNotifier.class);
	}

	private static NotificationProperties properties(String webhookUrl) {
		return new NotificationProperties(webhookUrl, Duration.ofSeconds(3), Duration.ofSeconds(5));
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
