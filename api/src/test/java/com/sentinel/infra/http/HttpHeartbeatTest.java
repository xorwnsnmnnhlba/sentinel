package com.sentinel.infra.http;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
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
 * 외부 감시 서비스는 JDK 내장 HTTP 서버로 대체
 */
@ExtendWith(OutputCaptureExtension.class)
class HttpHeartbeatTest {

	private HttpServer server;

	private final List<String> requests = new CopyOnWriteArrayList<>();

	private volatile int responseStatus = 200;

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/ping", exchange -> {
			requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
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
	void sendsAGetRequestToTheUrl() {
		heartbeat(url("/ping/check-token")).beat();

		assertThat(requests).containsExactly("GET /ping/check-token");
	}

	@Test
	void warnsOnceWhileFailingAndOnceOnRecoveryWithoutTheAddress(CapturedOutput output) {
		HttpHeartbeat heartbeat = heartbeat(url("/ping/secret-token"));
		responseStatus = 503;

		assertThatCode(() -> {
			heartbeat.beat();
			heartbeat.beat();
		}).doesNotThrowAnyException();
		responseStatus = 200;
		heartbeat.beat();
		heartbeat.beat();

		assertThat(requests).hasSize(4);
		String log = output.getAll();
		assertThat(log.split("Heartbeat ping failed \\(HTTP 503\\)", -1)).hasSize(2);
		assertThat(log.split("Heartbeat ping recovered", -1)).hasSize(2);
		assertThat(log).doesNotContain("secret-token").doesNotContain("127.0.0.1");
	}

	@Test
	void keepsPollingWhenTheMonitorIsUnreachable(CapturedOutput output) {
		HttpHeartbeat heartbeat = new HttpHeartbeat("http://127.0.0.1:%d/ping/secret-token".formatted(closedPort()),
				Duration.ofMillis(500));

		assertThatCode(heartbeat::beat).doesNotThrowAnyException();
		assertThat(output).contains("Heartbeat ping failed (connection failed)").doesNotContain("secret-token");
	}

	private HttpHeartbeat heartbeat(String url) {
		return new HttpHeartbeat(url, Duration.ofSeconds(2));
	}

	private String url(String path) {
		return "http://127.0.0.1:%d%s".formatted(server.getAddress().getPort(), path);
	}

	private static int closedPort() {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
