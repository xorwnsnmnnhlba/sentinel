package com.sentinel.infra.http;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import com.sentinel.runner.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import com.sentinel.runner.HttpMethod;
import com.sentinel.runner.RequestExecutionException;
import com.sentinel.runner.RequestExecutionException.Reason;
import com.sentinel.runner.RequestExecutor;
import com.sentinel.runner.ResolvedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
	void keepsOnlyTheFirstBytesOfAResponseLargerThanTheLimitAndCanSendAgain() {
		RestClientRequestExecutor limited = new RestClientRequestExecutor(Duration.ofSeconds(2), Duration.ofSeconds(1),
				Duration.ofSeconds(10), 1024, DestinationGuard.system());
		extraExecutors.add(limited);

		long started = System.nanoTime();

		// 5000바이트를 0.3초마다 나눠 보내는 응답 (전체 3초). 한도(1024)를 넘는 순간 남은 본문을 받지 않고 끊음
		HttpExchange exchange = limited.execute(ResolvedRequest.of(HttpMethod.GET, url("/large-body")));

		assertThat(exchange.statusCode()).isEqualTo(200);
		assertThat(exchange.body()).hasSize(1024).matches("a+");
		assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(2000));
		// 남은 본문을 읽지 않고 버린 연결은 다시 쓰지 않으므로 다음 요청도 정상
		assertThat(limited.execute(ResolvedRequest.of(HttpMethod.GET, url("/users/1"))).statusCode()).isEqualTo(200);
	}

	@Test
	void abortsAResponseBodyThatTakesLongerThanTheTotalTimeout() {
		// 0.2초마다 조금씩 보내 읽기 제한(1초)에는 걸리지 않지만 전체 4초 걸리는 응답
		RestClientRequestExecutor limited = new RestClientRequestExecutor(Duration.ofSeconds(2), Duration.ofSeconds(1),
				Duration.ofSeconds(1), RestClientRequestExecutor.DEFAULT_MAX_RESPONSE_BYTES, DestinationGuard.system());
		extraExecutors.add(limited);
		long started = System.nanoTime();

		assertThatThrownBy(() -> limited.execute(ResolvedRequest.of(HttpMethod.GET, url("/slow-body"))))
			.isInstanceOfSatisfying(RequestExecutionException.class,
					ex -> assertThat(ex.reason()).isEqualTo(RequestExecutionException.Reason.TIMEOUT));
		// 전체 제한(1초) + 읽기 한 번 안에 끝남. 남은 본문(약 3초)을 끝까지 받지 않음
		assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(2500));
	}

	@Test
	void readsASlowResponseBodyWithinTheTotalTimeout() {
		HttpExchange exchange = executor.execute(ResolvedRequest.of(HttpMethod.GET, url("/slow-body")));

		assertThat(exchange.body()).hasSize(2000);
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
	void sendsTheBodyWithItsLengthLikeAnOrdinaryClient() throws IOException {
		// 길이 없는 조각 전송(chunked)은 일부 서버·게이트웨이가 거부하거나 다르게 처리하여, 정상인 API가 실패로 보일 수 있음
		// (2026-10-17 새 서버 리허설에서 발견). 요청에 직접 넣은 잘못된 Content-Length도 실제 길이로 보냄
		List<String> framings = new ArrayList<>();
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			byte[] received = exchange.getRequestBody().readAllBytes();
			framings.add(exchange.getRequestHeaders().getFirst("Content-Length") + " / "
					+ exchange.getRequestHeaders().getFirst("Transfer-Encoding") + " / " + received.length);
			exchange.sendResponseHeaders(204, -1);
			exchange.close();
		});
		server.start();
		try {
			String base = "http://127.0.0.1:" + server.getAddress().getPort();
			executor.execute(new ResolvedRequest(HttpMethod.POST, URI.create(base + "/users"),
					Map.of("Content-Type", List.of("application/json")), "{\"name\":\"이순신\"}"));
			executor.execute(new ResolvedRequest(HttpMethod.PUT, URI.create(base + "/users/1"),
					Map.of("Content-Length", List.of("999")), "abc"));
			executor.execute(new ResolvedRequest(HttpMethod.GET, URI.create(base + "/users/1"), Map.of(), null));
		}
		finally {
			server.stop(0);
		}

		assertThat(framings).containsExactly("20 / null / 20", "3 / null / 3", "null / null / 0");
	}

	@Test
	void identifiesItselfAsSentinelUnlessTheCaseSetsAUserAgent() throws IOException {
		// 라이브러리 기본값(Apache-HttpClient/… (Java/…))은 보안 장비가 자동화 도구로 막고 Java 버전을 알려 줌
		List<String> userAgents = new ArrayList<>();
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			userAgents.add(exchange.getRequestHeaders().getFirst("User-Agent"));
			exchange.sendResponseHeaders(204, -1);
			exchange.close();
		});
		server.start();
		try {
			String base = "http://127.0.0.1:" + server.getAddress().getPort();
			executor.execute(new ResolvedRequest(HttpMethod.GET, URI.create(base + "/a"), Map.of(), null));
			executor.execute(new ResolvedRequest(HttpMethod.GET, URI.create(base + "/b"),
					Map.of("User-Agent", List.of("partner-app/2.1")), null));
		}
		finally {
			server.stop(0);
		}

		assertThat(userAgents).containsExactly(UserAgent.VALUE, "partner-app/2.1");
		assertThat(UserAgent.VALUE).startsWith("Sentinel/").doesNotContain("Java");
	}

	@Test
	void reportsAnUntrustedCertificateAsATlsError(@TempDir Path dir) throws Exception {
		// 인증서 만료·신뢰할 수 없는 발급자·이름 불일치는 일반 전송 오류(IO_ERROR)와 나눠 바로 알아보도록
		HttpsServer server = selfSignedHttpsServer(dir);
		server.start();
		try {
			ResolvedRequest request = new ResolvedRequest(HttpMethod.GET,
					URI.create("https://127.0.0.1:" + server.getAddress().getPort() + "/health"), Map.of(), null);

			assertThatThrownBy(() -> executor.execute(request)).isInstanceOfSatisfying(RequestExecutionException.class,
					ex -> assertThat(ex.reason()).isEqualTo(Reason.TLS_ERROR));
		}
		finally {
			server.stop(0);
		}
	}

	/** JDK keytool로 만든 일회용 자체 서명 인증서(신뢰할 수 없는 발급자)의 HTTPS 서버 */
	private static HttpsServer selfSignedHttpsServer(Path dir) throws Exception {
		Path keystore = dir.resolve("server.p12");
		Process keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
				"-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=localhost",
				"-ext", "SAN=dns:localhost", "-validity", "1", "-storetype", "PKCS12", "-keystore", keystore.toString(),
				"-storepass", "rehearsal")
			.redirectErrorStream(true)
			.start();
		assertThat(keytool.waitFor()).isZero();
		KeyStore keys = KeyStore.getInstance("PKCS12");
		try (InputStream in = Files.newInputStream(keystore)) {
			keys.load(in, "rehearsal".toCharArray());
		}
		KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
		keyManagers.init(keys, "rehearsal".toCharArray());
		SSLContext ssl = SSLContext.getInstance("TLS");
		ssl.init(keyManagers.getKeyManagers(), null, null);
		HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.setHttpsConfigurator(new HttpsConfigurator(ssl));
		server.createContext("/", exchange -> {
			exchange.sendResponseHeaders(200, -1);
			exchange.close();
		});
		return server;
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
