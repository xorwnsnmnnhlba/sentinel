package com.sentinel.api;

import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

import com.sentinel.TestAuthConfiguration;
import com.sentinel.TestcontainersConfiguration;
import com.sentinel.application.TestFixtures;
import com.sentinel.application.auth.AuthService;
import com.sentinel.application.secret.SecretCipher;
import com.sentinel.domain.Environment;
import com.sentinel.domain.Project;
import com.sentinel.domain.User;
import com.sentinel.infra.persistence.EnvironmentRepository;
import com.sentinel.infra.persistence.ProjectRepository;
import com.sentinel.infra.persistence.UserRepository;
import com.sentinel.infra.security.TestSecretCiphers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시스템 상태 (관리자). 설정은 지정 여부만, 주소·키 값은 응답에 없음. 키 교체 중 상태와 풀 수 없는 시크릿
 */
@SpringBootTest(properties = { "sentinel.secret.key=" + SystemStatusApiTest.KEY,
		"sentinel.secret.previous-key=" + SystemStatusApiTest.PREVIOUS_KEY,
		"sentinel.notification.webhook-url=" + SystemStatusApiTest.WEBHOOK,
		"sentinel.notification.web-base-url=https://sentinel.example.com",
		"sentinel.retention.raw-retention=14d" })
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, TestFixtures.class, TestAuthConfiguration.class })
class SystemStatusApiTest {

	static final String KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

	static final String PREVIOUS_KEY = "ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA=";

	static final String WEBHOOK = "https://hooks.example.com/services/system-status-test";

	/** 현재·이전 키 어느 것도 아닌 키 (분실한 키) */
	private static final SecretCipher LOST = TestSecretCiphers.withKey("MDAwMDAwMDAwMDAwMDAwMA==");

	@Autowired
	private MockMvcTester mvc;

	@Autowired
	private TestFixtures fixtures;

	@Autowired
	private ProjectRepository projects;

	@Autowired
	private EnvironmentRepository environments;

	@Autowired
	private UserRepository users;

	@Autowired
	private AuthService authService;

	@AfterEach
	void cleanUp() {
		fixtures.deleteAll();
	}

	@Test
	void showsVersionAndWhichSettingsAreConfiguredWithoutTheirValues() throws Exception {
		MvcTestResult result = mvc.get().uri("/api/system-status").exchange();

		var json = assertThat(result).hasStatusOk().bodyJson();
		json.extractingPath("$.build.version").isEqualTo("0.0.1-SNAPSHOT");
		json.extractingPath("$.build.builtAt").isNotNull();
		json.extractingPath("$.startedAt").asString().satisfies(at -> Instant.parse(at));
		json.extractingPath("$.databaseMigration").isEqualTo(latestMigration());
		json.extractingPath("$.secrets.state").isEqualTo("ROTATING");
		json.extractingPath("$.secrets.undecryptable").asArray().isEmpty();
		json.extractingPath("$.defaultChannel").isEqualTo(true);
		json.extractingPath("$.resultLinks").isEqualTo(true);
		json.extractingPath("$.heartbeat").isEqualTo(false);
		json.extractingPath("$.retention.rawResultDays").isEqualTo(14);
		json.extractingPath("$.retention.notificationDeliveryDays").isEqualTo(90);
		assertThat(result.getResponse().getContentAsString()).doesNotContain(WEBHOOK)
			.doesNotContain("sentinel.example.com")
			.doesNotContain(KEY)
			.doesNotContain(PREVIOUS_KEY);
	}

	@Test
	void listsActiveValuesThatNoConfiguredKeyCanDecrypt() throws Exception {
		Project broken = fixtures.project();
		broken.changeWebhook(LOST.encrypt(WEBHOOK));
		projects.save(broken);
		Environment environment = fixtures.environment(broken, Map.of());
		environment.putSecret("lostToken", LOST.encrypt("lost-token"));
		environment.putSecret("currentToken", TestSecretCiphers.withKey(KEY).encrypt("current-token"));
		environment.putSecret("previousToken", TestSecretCiphers.withKey(PREVIOUS_KEY).encrypt("previous-token"));
		environments.save(environment);
		Project working = fixtures.project();
		working.changeWebhook(TestSecretCiphers.withKey(KEY).encrypt(WEBHOOK));
		projects.save(working);
		// 보관된 항목은 화면에서 고칠 수 없으므로 제외
		Project archived = fixtures.project();
		archived.changeWebhook(LOST.encrypt(WEBHOOK));
		archived.archive(Instant.now());
		projects.save(archived);

		MvcTestResult result = mvc.get().uri("/api/system-status").exchange();

		var json = assertThat(result).hasStatusOk().bodyJson();
		json.extractingPath("$.secrets.undecryptable.length()").isEqualTo(2);
		// 프로젝트 Webhook 주소 먼저, 환경 시크릿은 이름까지
		json.extractingPath("$.secrets.undecryptable[0].project.id").isEqualTo(broken.getId().intValue());
		json.extractingPath("$.secrets.undecryptable[0].project.name").isEqualTo(broken.getName());
		json.extractingPath("$.secrets.undecryptable[0].environment").isNull();
		json.extractingPath("$.secrets.undecryptable[0].secret").isNull();
		json.extractingPath("$.secrets.undecryptable[1].project.id").isEqualTo(broken.getId().intValue());
		json.extractingPath("$.secrets.undecryptable[1].environment.id").isEqualTo(environment.getId().intValue());
		json.extractingPath("$.secrets.undecryptable[1].environment.name").isEqualTo("dev");
		json.extractingPath("$.secrets.undecryptable[1].secret").isEqualTo("lostToken");
		assertThat(result.getResponse().getContentAsString()).doesNotContain("lost-token").doesNotContain(WEBHOOK);
	}

	@Test
	void onlyAdministratorsCanSeeIt() {
		String password = UUID.randomUUID().toString();
		User member = users.save(new User("member-" + UUID.randomUUID() + "@sentinel.test", "팀원",
				TestAuthConfiguration.passwordHash(password), User.Role.MEMBER));
		String session = "Bearer " + authService.login(member.getEmail(), password).token();

		assertThat(mvc.get().uri("/api/system-status").header("Authorization", session))
			.hasStatus(HttpStatus.FORBIDDEN);
	}

	/** 마이그레이션 파일 중 가장 높은 버전 (새 마이그레이션이 추가되어도 따라감) */
	private static String latestMigration() throws Exception {
		Resource[] files = new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/V*__*.sql");
		return String.valueOf(Arrays.stream(files)
			.mapToInt(file -> Integer.parseInt(file.getFilename().substring(1, file.getFilename().indexOf("__"))))
			.max()
			.orElseThrow());
	}

}
