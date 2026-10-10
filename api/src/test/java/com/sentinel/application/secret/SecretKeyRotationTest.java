package com.sentinel.application.secret;

import java.time.Instant;
import java.util.Map;

import com.sentinel.TestcontainersConfiguration;
import com.sentinel.application.TestFixtures;
import com.sentinel.domain.Environment;
import com.sentinel.domain.Project;
import com.sentinel.infra.persistence.ChangeLogRepository;
import com.sentinel.infra.persistence.EnvironmentRepository;
import com.sentinel.infra.persistence.ProjectRepository;
import com.sentinel.infra.security.TestSecretCiphers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 키 교체 중(현재 키 + 이전 키) 기동 시 재암호화. 보관된 항목 포함, 풀 수 없는 값은 그대로 두고 위치만 경고
 */
@SpringBootTest(properties = { "sentinel.secret.key=" + SecretKeyRotationTest.KEY,
		"sentinel.secret.previous-key=" + SecretKeyRotationTest.PREVIOUS_KEY })
@Import({ TestcontainersConfiguration.class, TestFixtures.class })
@ExtendWith(OutputCaptureExtension.class)
class SecretKeyRotationTest {

	static final String KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

	static final String PREVIOUS_KEY = "ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA=";

	/** 현재·이전 키 어느 것도 아닌 키 (분실한 키) */
	private static final String LOST_KEY = "MDAwMDAwMDAwMDAwMDAwMA==";

	private static final String WEBHOOK = "https://hooks.example.com/services/rotation-test";

	private final SecretCipher current = TestSecretCiphers.withKey(KEY);

	private final SecretCipher previous = TestSecretCiphers.withKey(PREVIOUS_KEY);

	private final SecretCipher lost = TestSecretCiphers.withKey(LOST_KEY);

	@Autowired
	private SecretKeyRotation rotation;

	@Autowired
	private ProjectRepository projects;

	@Autowired
	private EnvironmentRepository environments;

	@Autowired
	private ChangeLogRepository changeLogs;

	@Autowired
	private TestFixtures fixtures;

	@AfterEach
	void cleanUp() {
		fixtures.deleteAll();
	}

	@Test
	void reEncryptsValuesSavedWithThePreviousKeyIncludingArchivedOnes(CapturedOutput output) {
		Project project = projectWithWebhook(previous.encrypt(WEBHOOK));
		Project archived = projectWithWebhook(previous.encrypt(WEBHOOK + "-archived"));
		archived.archive(Instant.now());
		projects.save(archived);
		// Webhook 없는 프로젝트는 대상 아님 (기본 채널)
		fixtures.project();
		Environment environment = fixtures.environment(project, Map.of());
		environment.putSecret("token", previous.encrypt("old-token"));
		environment.putSecret("apiKey", current.encrypt("new-api-key"));
		environments.save(environment);
		String alreadyCurrent = environments.findById(environment.getId())
			.orElseThrow()
			.getEncryptedSecrets()
			.get("apiKey");

		rotation.afterSingletonsInstantiated();

		assertThat(current.decrypt(projects.findById(project.getId()).orElseThrow().getWebhookUrlCipher()))
			.isEqualTo(WEBHOOK);
		assertThat(current.decrypt(projects.findById(archived.getId()).orElseThrow().getWebhookUrlCipher()))
			.isEqualTo(WEBHOOK + "-archived");
		Map<String, String> secrets = environments.findById(environment.getId()).orElseThrow().getEncryptedSecrets();
		assertThat(current.decrypt(secrets.get("token"))).isEqualTo("old-token");
		// 이미 현재 키인 값은 건드리지 않음
		assertThat(secrets.get("apiKey")).isEqualTo(alreadyCurrent);
		// 사용자 변경이 아니므로 변경 이력 없음
		assertThat(changeLogs.count()).isZero();
		assertThat(output).contains("Re-encrypted 3 secret value(s)")
			.doesNotContain(WEBHOOK)
			.doesNotContain("old-token")
			.doesNotContain("cannot be decrypted");
	}

	@Test
	void leavesValuesSavedWithAnotherKeyUnchangedAndNamesWhereTheyAre(CapturedOutput output) {
		String lostWebhook = lost.encrypt(WEBHOOK);
		Project project = projectWithWebhook(lostWebhook);
		Environment environment = fixtures.environment(project, Map.of());
		environment.putSecret("token", lost.encrypt("lost-token"));
		environment.putSecret("apiKey", previous.encrypt("old-api-key"));
		environments.save(environment);
		String lostToken = environments.findById(environment.getId()).orElseThrow().getEncryptedSecrets().get("token");

		SecretKeyRotation.Result result = rotation.rotate();

		assertThat(result.reEncrypted()).isEqualTo(1);
		assertThat(result.undecryptable()).containsExactly("project " + project.getId() + " webhook",
				"environment " + environment.getId() + " secret 'token'");
		assertThat(projects.findById(project.getId()).orElseThrow().getWebhookUrlCipher()).isEqualTo(lostWebhook);
		Map<String, String> secrets = environments.findById(environment.getId()).orElseThrow().getEncryptedSecrets();
		assertThat(secrets.get("token")).isEqualTo(lostToken);
		assertThat(current.decrypt(secrets.get("apiKey"))).isEqualTo("old-api-key");

		rotation.afterSingletonsInstantiated();

		assertThat(output).contains("2 secret value(s) cannot be decrypted")
			.contains("environment " + environment.getId() + " secret 'token'")
			.contains("SENTINEL_SECRET_KEY_PREVIOUS")
			.doesNotContain("lost-token")
			.doesNotContain(WEBHOOK);
	}

	private Project projectWithWebhook(String cipherText) {
		Project project = fixtures.project();
		project.changeWebhook(cipherText);
		return projects.save(project);
	}

}
