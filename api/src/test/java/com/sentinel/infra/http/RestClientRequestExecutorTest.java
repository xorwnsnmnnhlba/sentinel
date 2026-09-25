package com.sentinel.infra.http;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.sentinel.runner.HttpExchange;
import com.sentinel.runner.HttpMethod;
import com.sentinel.runner.RequestExecutionException;
import com.sentinel.runner.RequestExecutionException.Reason;
import com.sentinel.runner.RequestExecutor;
import com.sentinel.runner.ResolvedRequest;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class RestClientRequestExecutorTest {

	@Container
	static final GenericContainer<?> wiremock = new GenericContainer<>(DockerImageName.parse("wiremock/wiremock:3.13.2"))
			.withExposedPorts(8080)
			.withCopyFileToContainer(MountableFile.forClasspathResource("wiremock/mappings"), "/home/wiremock/mappings")
			.waitingFor(Wait.forHttp("/__admin/health").forStatusCode(200));

	private final RequestExecutor executor = new RestClientRequestExecutor(Duration.ofSeconds(2), Duration.ofSeconds(1));

	@Test
	void returnsStatusHeadersAndBody() {
		HttpExchange exchange = executor.execute(ResolvedRequest.of(HttpMethod.GET, url("/users/1")));

		assertThat(exchange.statusCode()).isEqualTo(200);
		assertThat(exchange.headers().get("X-Trace-Id")).containsExactly("trace-123");
		assertThat(exchange.headers().get("content-type")).containsExactly("application/json");
		assertThat(exchange.headers()).doesNotContainKey(":status");
		assertThat(exchange.body()).isEqualTo("{\"id\":1,\"name\":\"kim\"}");
		assertThat(exchange.elapsed()).isPositive();
	}

	@Test
	void sendsHeadersAndUtf8Body() {
		ResolvedRequest request = new ResolvedRequest(HttpMethod.POST, URI.create(url("/users")),
				Map.of("Authorization", List.of("Bearer test-token"), "Content-Type", List.of("application/json")),
				"{\"name\":\"이순신\"}");

		HttpExchange exchange = executor.execute(request);

		assertThat(exchange.statusCode()).isEqualTo(201);
		assertThat(exchange.body()).isEqualTo("created");
	}

	@Test
	void returnsErrorStatusWithoutThrowing() {
		assertThat(executor.execute(ResolvedRequest.of(HttpMethod.GET, url("/errors/500"))).statusCode())
				.isEqualTo(500);
		assertThat(executor.execute(ResolvedRequest.of(HttpMethod.GET, url("/not-registered"))).statusCode())
				.isEqualTo(404);
	}

	@Test
	void doesNotFollowRedirect() {
		HttpExchange exchange = executor.execute(ResolvedRequest.of(HttpMethod.GET, url("/redirect")));

		assertThat(exchange.statusCode()).isEqualTo(302);
		assertThat(exchange.headers().get("Location")).containsExactly("/users/1");
	}

	@Test
	void decodesBodyWithResponseCharset() {
		HttpExchange exchange = executor.execute(ResolvedRequest.of(HttpMethod.GET, url("/euc-kr")));

		assertThat(exchange.body()).isEqualTo("안녕");
	}

	@Test
	void throwsTimeoutWhenResponseIsSlowerThanReadTimeout() {
		ResolvedRequest request = ResolvedRequest.of(HttpMethod.GET, url("/slow"));

		assertThatThrownBy(() -> executor.execute(request))
				.isInstanceOfSatisfying(RequestExecutionException.class, ex -> {
					assertThat(ex.reason()).isEqualTo(Reason.TIMEOUT);
					assertThat(ex.request()).isEqualTo(request);
					assertThat(ex.elapsed()).isGreaterThanOrEqualTo(Duration.ofSeconds(1));
				});
	}

	@Test
	void throwsConnectionFailedWhenNothingListens() throws IOException {
		int closedPort;
		try (ServerSocket socket = new ServerSocket(0)) {
			closedPort = socket.getLocalPort();
		}

		assertThatThrownBy(() -> executor.execute(ResolvedRequest.of(HttpMethod.GET, "http://localhost:" + closedPort)))
				.isInstanceOfSatisfying(RequestExecutionException.class,
						ex -> assertThat(ex.reason()).isEqualTo(Reason.CONNECTION_FAILED));
	}

	@Test
	void throwsConnectionFailedWhenHostIsUnknown() {
		assertThatThrownBy(() -> executor.execute(ResolvedRequest.of(HttpMethod.GET, "http://sentinel.invalid/")))
				.isInstanceOfSatisfying(RequestExecutionException.class,
						ex -> assertThat(ex.reason()).isEqualTo(Reason.CONNECTION_FAILED));
	}

	private static String url(String path) {
		return "http://" + wiremock.getHost() + ":" + wiremock.getMappedPort(8080) + path;
	}


	@Test
	void refusesCloudMetadataAddressesWithoutSending() {
		ResolvedRequest request = new ResolvedRequest(HttpMethod.GET,
				URI.create("http://169.254.169.254/latest/meta-data/iam/security-credentials/"), Map.of(), null);

		assertThatThrownBy(() -> executor.execute(request)).isInstanceOfSatisfying(RequestExecutionException.class,
				ex -> {
					assertThat(ex.reason()).isEqualTo(RequestExecutionException.Reason.BLOCKED_DESTINATION);
					assertThat(ex.elapsed()).isZero();
					assertThat(ex.getMessage()).isEqualTo(
							"BLOCKED_DESTINATION: 169.254.169.254 resolves to 169.254.169.254, a link-local or cloud metadata address");
				});
	}

}
