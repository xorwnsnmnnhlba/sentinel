package com.sentinel.api;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sentinel.TestAuthConfiguration;
import com.sentinel.TestcontainersConfiguration;
import com.sentinel.application.FakeRequestExecutorConfiguration;
import com.sentinel.application.TestFixtures;
import com.sentinel.application.auth.AuthService;
import com.sentinel.domain.User;
import com.sentinel.infra.persistence.UserRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 서버 기본 채널 연결 확인 알림 ({@code POST /api/notification-deliveries}, projectId 없음). 기본 채널은 JDK 내장 HTTP 서버.
 * 기본 채널이 없을 때(409)는 ProjectWebhookApiTest
 */
@SpringBootTest(properties = "sentinel.schedule.enabled=false")
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, FakeRequestExecutorConfiguration.class, TestFixtures.class,
		TestAuthConfiguration.class })
class DefaultChannelTestNotificationApiTest {

	private static final List<String> received = new CopyOnWriteArrayList<>();

	private static volatile int responseStatus = 200;

	private static final HttpServer server = start();

	@Autowired
	private MockMvcTester mvc;

	@Autowired
	private TestFixtures fixtures;

	@Autowired
	private UserRepository users;

	@Autowired
	private AuthService authService;

	@DynamicPropertySource
	static void defaultChannel(DynamicPropertyRegistry registry) {
		registry.add("sentinel.notification.webhook-url",
				() -> "http://127.0.0.1:" + server.getAddress().getPort() + "/default-channel");
	}

	@AfterEach
	void cleanUp() {
		fixtures.deleteAll();
		received.clear();
		responseStatus = 200;
	}

	@AfterAll
	static void stopServer() {
		server.stop(0);
	}

	@Test
	void sendsATestNotificationThroughTheDefaultChannelAndRecordsIt() {
		var sent = assertThat(mvc.post().uri("/api/notification-deliveries")).hasStatus(HttpStatus.CREATED).bodyJson();
		sent.extractingPath("$.channel").isEqualTo("DEFAULT");
		sent.extractingPath("$.project").isNull();
		sent.extractingPath("$.delivered").isEqualTo(true);
		sent.extractingPath("$.statusCode").isEqualTo(200);
		assertThat(received).singleElement()
			.asString()
			.contains("[Sentinel] 기본 알림 채널 연결 확인")
			.contains("보낸 사람: tester-");

		responseStatus = 404;
		var rejected = assertThat(mvc.post()
			.uri("/api/notification-deliveries")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}")).hasStatus(HttpStatus.CREATED).bodyJson();
		rejected.extractingPath("$.delivered").isEqualTo(false);
		rejected.extractingPath("$.statusCode").isEqualTo(404);

		// 실제 알림과 같이 발송 이력·채널 상태에 반영
		var history = assertThat(mvc.get().uri("/api/notification-deliveries")).bodyJson();
		history.extractingPath("$.content[*].statusCode").isEqualTo(List.of(404, 200));
		history.extractingPath("$.content[0].channel").isEqualTo("DEFAULT");
		history.extractingPath("$.content[0].title").isEqualTo("[Sentinel] 기본 알림 채널 연결 확인");
		var channels = assertThat(mvc.get().uri("/api/notification-channels")).bodyJson();
		channels.extractingPath("$[0].configured").isEqualTo(true);
		channels.extractingPath("$[0].format").isEqualTo("TEXT");
		channels.extractingPath("$[0].failing.statusCode").isEqualTo(404);
	}

	@Test
	void onlyAdministratorsCanSendIt() {
		String password = UUID.randomUUID().toString();
		User member = users.save(new User("member-" + UUID.randomUUID() + "@sentinel.test", "구성원",
				TestAuthConfiguration.passwordHash(password), User.Role.MEMBER));
		String session = "Bearer " + authService.login(member.getEmail(), password).token();

		assertThat(mvc.post().uri("/api/notification-deliveries").header("Authorization", session))
			.hasStatus(HttpStatus.FORBIDDEN);
		assertThat(received).isEmpty();
	}

	private static HttpServer start() {
		try {
			HttpServer created = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			created.createContext("/default-channel", exchange -> {
				try (InputStream body = exchange.getRequestBody()) {
					received.add(new String(body.readAllBytes(), StandardCharsets.UTF_8));
				}
				exchange.sendResponseHeaders(responseStatus, -1);
				exchange.close();
			});
			created.start();
			return created;
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
