package com.sentinel.application.notification;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sentinel.infra.http.RestClientRequestExecutor;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 프로젝트 Webhook 발송. 수신 서버는 JDK 내장 HTTP 서버 */
class WebhookSenderTest {

	private HttpServer server;

	private final List<String> bodies = new CopyOnWriteArrayList<>();

	private final List<String> contentTypes = new CopyOnWriteArrayList<>();

	private volatile int responseStatus = 200;

	private final WebhookSender sender = new WebhookSender(
			new RestClientRequestExecutor(Duration.ofSeconds(2), Duration.ofSeconds(2)));

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/hook", exchange -> {
			try (InputStream body = exchange.getRequestBody()) {
				bodies.add(new String(body.readAllBytes(), StandardCharsets.UTF_8));
			}
			contentTypes.add(String.valueOf(exchange.getRequestHeaders().getFirst("Content-Type")));
			if (responseStatus == 302) {
				exchange.getResponseHeaders().add("Location", url());
			}
			exchange.sendResponseHeaders(responseStatus, -1);
			exchange.close();
		});
		server.start();
	}

	@AfterEach
	void stop() throws Exception {
		server.stop(0);
		sender.close();
	}

	@Test
	void postsTheTextAsJsonLikeTheDefaultChannel() {
		WebhookSender.Delivery delivery = sender.deliver(url(), new Notification("[Sentinel] 확인", List.of("a", "b")));

		assertThat(delivery).isEqualTo(new WebhookSender.Delivery(true, 200, null));
		assertThat(bodies).containsExactly("{\"text\":\"[Sentinel] 확인\\na\\nb\"}");
		assertThat(contentTypes.getFirst()).startsWith("application/json");
	}

	@Test
	void reportsErrorResponsesAndDoesNotFollowRedirects() {
		responseStatus = 404;
		assertThat(sender.deliver(url(), new Notification("x", List.of())))
			.isEqualTo(new WebhookSender.Delivery(false, 404, null));

		responseStatus = 302;
		assertThat(sender.deliver(url(), new Notification("x", List.of())))
			.isEqualTo(new WebhookSender.Delivery(false, 302, null));
		assertThat(bodies).hasSize(2);
	}

	@Test
	void reportsWhyNothingWasReceivedWithoutTheUrl() throws IOException {
		int closedPort;
		try (ServerSocket socket = new ServerSocket(0)) {
			closedPort = socket.getLocalPort();
		}

		assertThat(sender.deliver("http://127.0.0.1:" + closedPort + "/hook", new Notification("x", List.of())))
			.isEqualTo(new WebhookSender.Delivery(false, null, "CONNECTION_FAILED"));
		// 클라우드 메타데이터 주소는 보내지 않음 (누구나 지정할 수 있는 주소)
		assertThat(sender.deliver("http://169.254.169.254/latest/meta-data", new Notification("x", List.of())))
			.isEqualTo(new WebhookSender.Delivery(false, null, "BLOCKED_DESTINATION"));
		assertThat(sender.deliver("::not a url", new Notification("x", List.of())))
			.isEqualTo(new WebhookSender.Delivery(false, null, "INVALID_URL"));
	}

	private String url() {
		return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
	}

}
