package com.sentinel.api;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sentinel.TestAuthConfiguration;
import com.sentinel.TestcontainersConfiguration;
import com.sentinel.application.FakeRequestExecutor;
import com.sentinel.application.FakeRequestExecutorConfiguration;
import com.sentinel.application.TestFixtures;
import com.sentinel.application.auth.AuthService;
import com.sentinel.application.notification.RecordingNotifier;
import com.sentinel.application.notification.RunResultNotifier;
import com.sentinel.domain.Environment;
import com.sentinel.domain.NotificationDelivery;
import com.sentinel.domain.Project;
import com.sentinel.domain.TestRun;
import com.sentinel.domain.TestSuite;
import com.sentinel.domain.User;
import com.sentinel.infra.persistence.NotificationDeliveryRepository;
import com.sentinel.infra.persistence.ProjectRepository;
import com.sentinel.infra.persistence.UserRepository;
import com.sentinel.runner.HttpMethod;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 프로젝트별 알림 채널. Webhook 지정·삭제, 주소 비공개, 연결 확인 알림, 실패 알림 발송 경로.
 * 수신 서버는 JDK 내장 HTTP 서버, 서버 기본 채널은 기록용 채널
 */
@SpringBootTest(properties = { "sentinel.schedule.enabled=false",
		"sentinel.secret.key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=" })
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@Import({ TestcontainersConfiguration.class, FakeRequestExecutorConfiguration.class, TestFixtures.class,
		TestAuthConfiguration.class, ProjectWebhookApiTest.DefaultChannel.class })
class ProjectWebhookApiTest {

	@Autowired
	private MockMvcTester mvc;

	@Autowired
	private TestFixtures fixtures;

	@Autowired
	private ProjectRepository projects;

	@Autowired
	private RunResultNotifier runResultNotifier;

	@Autowired
	private FakeRequestExecutor executor;

	@Autowired
	private RecordingNotifier defaultChannel;

	@Autowired
	private NotificationDeliveryRepository deliveryRecords;

	@Autowired
	private UserRepository users;

	@Autowired
	private AuthService authService;

	private HttpServer server;

	private final List<String> received = new CopyOnWriteArrayList<>();

	private volatile int responseStatus = 200;

	private Project project;

	@BeforeEach
	void setUp() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/hook", exchange -> {
			try (InputStream body = exchange.getRequestBody()) {
				received.add(new String(body.readAllBytes(), StandardCharsets.UTF_8));
			}
			exchange.sendResponseHeaders(responseStatus, -1);
			exchange.close();
		});
		server.start();
		project = fixtures.project();
	}

	@AfterEach
	void cleanUp() {
		server.stop(0);
		fixtures.deleteAll();
		executor.reset();
		defaultChannel.reset();
	}

	@Test
	void storesTheWebhookEncryptedAndNeverShowsIt() {
		MvcTestResult updated = patch("{\"webhookUrl\": \"%s\"}".formatted(url()));

		assertThat(updated).hasStatusOk().bodyJson().extractingPath("$.webhookConfigured").isEqualTo(true);
		assertThat(body(updated)).doesNotContain(url());
		assertThat(body(mvc.get().uri("/api/projects/{id}", project.getId()).exchange())).doesNotContain(url())
			.contains("\"webhookConfigured\":true");
		// 평문은 저장하지 않음
		String stored = projects.findById(project.getId()).orElseThrow().getWebhookUrlCipher();
		assertThat(stored).isNotBlank().doesNotContain("127.0.0.1").doesNotContain("/hook");
		// 변경 이력에는 항목 이름만
		MvcTestResult logs = mvc.get().uri("/api/change-logs?targetType=PROJECT&targetId={id}", project.getId())
			.exchange();
		assertThat(logs).bodyJson().extractingPath("$.content[0].fields").isEqualTo(List.of("webhook"));
		assertThat(body(logs)).doesNotContain(url());
	}

	@Test
	void sendsATestNotificationAndReportsTheResult() {
		patch("{\"webhookUrl\": \"%s\"}".formatted(url()));

		MvcTestResult created = testNotification();
		var sent = assertThat(created).hasStatus(HttpStatus.CREATED).bodyJson();
		sent.extractingPath("$.delivered").isEqualTo(true);
		sent.extractingPath("$.statusCode").isEqualTo(200);
		sent.extractingPath("$.channel").isEqualTo("PROJECT");
		// 만든 발송 기록은 Location으로 조회
		assertThat(mvc.get().uri(created.getResponse().getHeader("Location"))).hasStatusOk()
			.bodyJson()
			.extractingPath("$.project.name")
			.isEqualTo(project.getName());
		assertThat(received).singleElement()
			.asString()
			.contains(project.getName() + " 알림 연결 확인")
			.contains("보낸 사람: tester-");
		assertThat(defaultChannel.sent()).isEmpty();

		responseStatus = 404;
		var rejected = assertThat(testNotification()).hasStatus(HttpStatus.CREATED).bodyJson();
		rejected.extractingPath("$.delivered").isEqualTo(false);
		rejected.extractingPath("$.statusCode").isEqualTo(404);

		// 발송 이력: 최신순, 프로젝트 채널, 주소는 남기지 않음
		MvcTestResult deliveries = mvc.get()
			.uri("/api/notification-deliveries?projectId={id}", project.getId())
			.exchange();
		var history = assertThat(deliveries).hasStatusOk().bodyJson();
		history.extractingPath("$.content[*].delivered").isEqualTo(List.of(false, true));
		history.extractingPath("$.content[*].statusCode").isEqualTo(List.of(404, 200));
		history.extractingPath("$.content[0].channel").isEqualTo("PROJECT");
		history.extractingPath("$.content[0].format").isEqualTo("TEXT");
		history.extractingPath("$.content[0].project.name").isEqualTo(project.getName());
		history.extractingPath("$.content[0].title").isEqualTo("[Sentinel] " + project.getName() + " 알림 연결 확인");
		history.extractingPath("$.content[0].tone").isEqualTo("INFO");
		assertThat(body(deliveries)).doesNotContain("127.0.0.1").doesNotContain("/hook");
		assertThat(mvc.get().uri("/api/notification-deliveries?projectId={id}&failed=true", project.getId()))
			.bodyJson()
			.extractingPath("$.content[*].statusCode")
			.isEqualTo(List.of(404));

		// 채널 상태: 마지막 발송이 실패한 채널. 기본 채널은 주소가 없어 실패 기록이 있어도 판단하지 않음
		deliveryRecords.save(new NotificationDelivery(Instant.now(), NotificationDelivery.Channel.DEFAULT, null,
				NotificationDelivery.Format.TEXT, "예전 기본 채널 실패", NotificationDelivery.Tone.FAILURE, false, 404, null));
		var channels = assertThat(mvc.get().uri("/api/notification-channels")).hasStatusOk().bodyJson();
		channels.extractingPath("$[*].channel").isEqualTo(List.of("DEFAULT", "PROJECT"));
		channels.extractingPath("$[0].configured").isEqualTo(false);
		channels.extractingPath("$[0].format").isEqualTo("LOG");
		channels.extractingPath("$[0].failing").isNull();
		channels.extractingPath("$[1].project.name").isEqualTo(project.getName());
		channels.extractingPath("$[1].format").isEqualTo("TEXT");
		channels.extractingPath("$[1].failing.statusCode").isEqualTo(404);
		channels.extractingPath("$[1].failing.failures").isEqualTo(1);
		responseStatus = 200;
		testNotification();
		assertThat(mvc.get().uri("/api/notification-channels")).bodyJson().extractingPath("$[1].failing").isNull();
	}

	@Test
	void refusesToSendToCloudMetadataAddresses() {
		patch("{\"webhookUrl\": \"http://169.254.169.254/latest/meta-data\"}");

		var blocked = assertThat(testNotification()).hasStatus(HttpStatus.CREATED).bodyJson();
		blocked.extractingPath("$.delivered").isEqualTo(false);
		blocked.extractingPath("$.error").isEqualTo("BLOCKED_DESTINATION");
		blocked.extractingPath("$.statusCode").isNull();
		assertThat(mvc.get().uri("/api/notification-deliveries?projectId={id}", project.getId())).bodyJson()
			.extractingPath("$.content[0].error")
			.isEqualTo("BLOCKED_DESTINATION");
	}

	@Test
	void removingTheWebhookFallsBackToTheDefaultChannel() {
		patch("{\"webhookUrl\": \"%s\"}".formatted(url()));

		assertThat(patch("{\"webhookUrl\": \"\"}")).bodyJson().extractingPath("$.webhookConfigured").isEqualTo(false);

		assertThat(testNotification()).hasStatus(HttpStatus.CONFLICT)
			.bodyJson()
			.extractingPath("$.detail")
			.isEqualTo("project " + project.getId() + " has no webhook");
		// 이름만 바꾸면 Webhook은 그대로, 없는 Webhook을 다시 지워도 이력 없음
		patch("{\"webhookUrl\": \"%s\"}".formatted(url()));
		assertThat(patch("{\"name\": \"renamed-%d\"}".formatted(project.getId()))).bodyJson()
			.extractingPath("$.webhookConfigured")
			.isEqualTo(true);
		patch("{\"webhookUrl\": \"\"}");
		patch("{\"webhookUrl\": \"\"}");
		assertThat(mvc.get().uri("/api/change-logs?targetType=PROJECT&targetId={id}", project.getId())).bodyJson()
			.extractingPath("$.content[*].fields")
			.isEqualTo(List.of(List.of("webhook"), List.of("name"), List.of("webhook"), List.of("webhook"),
					List.of("webhook")));
	}

	@Test
	void anyMemberCanTestAProjectChannel() {
		patch("{\"webhookUrl\": \"%s\"}".formatted(url()));
		String password = UUID.randomUUID().toString();
		User member = users.save(new User("member-" + UUID.randomUUID() + "@sentinel.test", "팀원",
				TestAuthConfiguration.passwordHash(password), User.Role.MEMBER));
		String session = "Bearer " + authService.login(member.getEmail(), password).token();

		assertThat(mvc.post()
			.uri("/api/notification-deliveries")
			.header("Authorization", session)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"projectId\": %d}".formatted(project.getId()))).hasStatus(HttpStatus.CREATED);
		assertThat(received).singleElement().asString().contains("보낸 사람: " + member.getEmail());
		// 기본 채널은 관리자만
		assertThat(mvc.post().uri("/api/notification-deliveries").header("Authorization", session))
			.hasStatus(HttpStatus.FORBIDDEN);
	}

	@Test
	void cannotTestTheDefaultChannelWhenItIsNotConfigured() {
		assertThat(mvc.post().uri("/api/notification-deliveries")).hasStatus(HttpStatus.CONFLICT)
			.bodyJson()
			.extractingPath("$.detail")
			.isEqualTo("default notification channel is not configured");
		assertThat(defaultChannel.sent()).isEmpty();
	}

	@Test
	void rejectsInvalidWebhooks() {
		assertThat(patch("{\"webhookUrl\": \"ftp://hooks.example.test/x\"}")).hasStatus(HttpStatus.BAD_REQUEST)
			.bodyJson()
			.extractingPath("$.detail")
			.isEqualTo("webhookUrl must be an absolute http(s) URL");
		assertThat(mvc.post()
			.uri("/api/notification-deliveries")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"projectId\": -1}")).hasStatus(HttpStatus.NOT_FOUND);
	}

	@Test
	void sendsFailureAlertsOfTheProjectToItsWebhook() {
		Environment environment = fixtures.environment(project, Map.of());
		TestSuite suite = fixtures.suite(project);
		fixtures.testCase(suite, HttpMethod.GET, "/broken", null, null);
		executor.respondStatus(request -> 500);
		patch("{\"webhookUrl\": \"%s\"}".formatted(url()));

		long runId = fixtures.runSuite(suite.getId(), environment.getId(), TestRun.Trigger.SCHEDULED, null);

		assertThat(runResultNotifier.notifyResult(runId)).isEqualTo(RunResultNotifier.Sent.FAILURE);
		assertThat(received).singleElement().asString().contains(suite.getName()).contains("GET /broken");
		assertThat(defaultChannel.sent()).isEmpty();
		var history = assertThat(mvc.get().uri("/api/notification-deliveries")).bodyJson();
		history.extractingPath("$.content[0].tone").isEqualTo("FAILURE");
		history.extractingPath("$.content[0].delivered").isEqualTo(true);
		history.extractingPath("$.content[0].project.id").isEqualTo(project.getId().intValue());
	}

	@Test
	void sendsAlertsOfProjectsWithoutAWebhookToTheDefaultChannelQuietly(CapturedOutput output) {
		Environment environment = fixtures.environment(project, Map.of());
		TestSuite suite = fixtures.suite(project);
		fixtures.testCase(suite, HttpMethod.GET, "/broken", null, null);
		executor.respondStatus(request -> 500);

		long runId = fixtures.runSuite(suite.getId(), environment.getId(), TestRun.Trigger.SCHEDULED, null);
		runResultNotifier.notifyResult(runId);

		assertThat(defaultChannel.sent()).hasSize(1);
		assertThat(received).isEmpty();
		// 지정하지 않은 것은 문제가 아니므로 경고 없음
		assertThat(output.getOut()).doesNotContain("Webhook of project " + project.getId());
	}

	@Test
	void sendsToTheDefaultChannelWhenTheWebhookCannotBeRead(CapturedOutput output) {
		Environment environment = fixtures.environment(project, Map.of());
		TestSuite suite = fixtures.suite(project);
		fixtures.testCase(suite, HttpMethod.GET, "/broken", null, null);
		executor.respondStatus(request -> 500);
		// 다른 키로 암호화했거나 손상된 값
		Project stored = projects.findById(project.getId()).orElseThrow();
		stored.changeWebhook("not-a-cipher-text");
		projects.save(stored);

		long runId = fixtures.runSuite(suite.getId(), environment.getId(), TestRun.Trigger.SCHEDULED, null);
		runResultNotifier.notifyResult(runId);

		assertThat(defaultChannel.sent()).hasSize(1);
		assertThat(received).isEmpty();
		assertThat(output.getOut()).contains("Webhook of project " + project.getId() + " cannot be read");
		assertThat(testNotification()).hasStatus(HttpStatus.BAD_REQUEST);
	}

	private MvcTestResult patch(String body) {
		return mvc.patch()
			.uri("/api/projects/{id}", project.getId())
			.contentType(MediaType.APPLICATION_JSON)
			.content(body)
			.exchange();
	}

	private MvcTestResult testNotification() {
		return mvc.post()
			.uri("/api/notification-deliveries")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"projectId\": %d}".formatted(project.getId()))
			.exchange();
	}

	private String url() {
		return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
	}

	private static String body(MvcTestResult result) {
		try {
			return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
		}
		catch (UnsupportedEncodingException ex) {
			throw new IllegalStateException(ex);
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class DefaultChannel {

		@Bean
		@Primary
		RecordingNotifier recordingNotifier() {
			return new RecordingNotifier();
		}

	}

}
