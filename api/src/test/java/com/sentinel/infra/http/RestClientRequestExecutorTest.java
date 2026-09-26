package com.sentinel.infra.http;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.sentinel.runner.HttpExchange;
import com.sentinel.runner.HttpMethod;
import com.sentinel.runner.RequestExecutionException;
import com.sentinel.runner.RequestExecutionException.Reason;
import com.sentinel.runner.RequestExecutor;
import com.sentinel.runner.ResolvedRequest;
import org.junit.jupiter.api.AfterEach;
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

	private final RestClientRequestExecutor executor = new RestClientRequestExecutor(Duration.ofSeconds(2),
			Duration.ofSeconds(1));

	private final List<RestClientRequestExecutor> extraExecutors = new ArrayList<>();

	@AfterEach
	void closeExecutors() throws IOException {
		executor.close();
		for (RestClientRequestExecutor extra : extraExecutors) {
			extra.close();
		}
	}

	/** 시스템 DNS 대신 주어진 해석기를 쓰는 실행기 */
	private RequestExecutor executorResolvingWith(DestinationGuard.Resolver resolver, Duration connectTimeout) {
		RestClientRequestExecutor extra = new RestClientRequestExecutor(connectTimeout, Duration.ofSeconds(1),
				new DestinationGuard(resolver));
		extraExecutors.add(extra);
		return extra;
	}

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

	@Test
	void connectsOnlyToTheAddressItChecked() throws UnknownHostException {
		// DNS rebinding: 확인할 때는 정상 주소, 다시 물으면 메타데이터 주소를 주는 이름
		InetAddress wiremockAddress = InetAddress.getByName(wiremock.getHost());
		AtomicInteger lookups = new AtomicInteger();
		RequestExecutor rebinding = executorResolvingWith(host -> {
			if (!host.equals("api.rebind.test")) {
				throw new UnknownHostException(host);
			}
			return lookups.getAndIncrement() == 0 ? new InetAddress[] { wiremockAddress }
					: new InetAddress[] { InetAddress.getByName("169.254.169.254") };
		}, Duration.ofSeconds(2));

		HttpExchange exchange = rebinding.execute(ResolvedRequest.of(HttpMethod.GET,
				"http://api.rebind.test:" + wiremock.getMappedPort(8080) + "/users/1"));

		// 시스템 DNS가 모르는 이름이므로 연결 성공은 확인한 주소로 연결했다는 뜻. 확인과 연결 사이 재조회 없음
		assertThat(exchange.statusCode()).isEqualTo(200);
		assertThat(lookups).hasValue(1);
	}

	@Test
	void refusesHostNamesResolvingToMetadataAddressesWithoutConnecting() {
		AtomicInteger lookups = new AtomicInteger();
		RequestExecutor executor = executorResolvingWith(host -> {
			lookups.incrementAndGet();
			return new InetAddress[] { InetAddress.getByName("169.254.169.254") };
		}, Duration.ofSeconds(2));
		ResolvedRequest request = ResolvedRequest.of(HttpMethod.GET, "http://metadata.google.internal/computeMetadata/v1/");

		assertThatThrownBy(() -> executor.execute(request)).isInstanceOfSatisfying(RequestExecutionException.class,
				ex -> {
					assertThat(ex.reason()).isEqualTo(Reason.BLOCKED_DESTINATION);
					assertThat(ex.elapsed()).isZero();
					assertThat(ex.getMessage()).isEqualTo(
							"BLOCKED_DESTINATION: metadata.google.internal resolves to 169.254.169.254, a link-local or cloud metadata address");
				});
		assertThat(lookups).hasValue(1);
	}

	@Test
	void throwsConnectionFailedWhenConnectTimesOut() {
		// TEST-NET-1(192.0.2.0/24): 문서용 대역이라 응답 없음
		RequestExecutor blackhole = executorResolvingWith(host -> new InetAddress[] { InetAddress.getByName("192.0.2.1") },
				Duration.ofMillis(300));

		assertThatThrownBy(() -> blackhole.execute(ResolvedRequest.of(HttpMethod.GET, "http://slow-connect.test/")))
			.isInstanceOfSatisfying(RequestExecutionException.class,
					ex -> assertThat(ex.reason()).isEqualTo(Reason.CONNECTION_FAILED));
	}

	@Test
	void sendsRequestsWithoutAddingCompressionOrCookies() {
		// 이전 응답의 쿠키를 다음 요청에 싣지 않음 (케이스마다 적은 헤더만 전송)
		executor.execute(ResolvedRequest.of(HttpMethod.GET, url("/set-cookie")));
		executor.execute(ResolvedRequest.of(HttpMethod.GET, url("/users/1")));

		String journal = executor.execute(ResolvedRequest.of(HttpMethod.GET, url("/__admin/requests?limit=2"))).body();

		// 서버가 준 그대로의 응답을 기록하도록 압축 요청 없음
		assertThat(journal).contains("/users/1", "/set-cookie")
			.doesNotContainIgnoringCase("Accept-Encoding")
			.doesNotContain("session=abc\"");
	}

	@Test
	void reportsUnavailableResponsesWithoutRetrying() {
		String path = "/unavailable?id=" + System.nanoTime();

		HttpExchange exchange = executor.execute(ResolvedRequest.of(HttpMethod.GET, url(path)));

		assertThat(exchange.statusCode()).isEqualTo(503);
		// 503·429에 Retry-After가 있어도 다시 보내지 않음 (첫 응답이 검증 대상)
		HttpExchange count = executor.execute(new ResolvedRequest(HttpMethod.POST, URI.create(url("/__admin/requests/count")),
				Map.of("Content-Type", List.of("application/json")), "{\"method\":\"GET\",\"url\":\"" + path + "\"}"));
		assertThat(count.body()).containsPattern("\"count\"\\s*:\\s*1\\b");
	}

}
