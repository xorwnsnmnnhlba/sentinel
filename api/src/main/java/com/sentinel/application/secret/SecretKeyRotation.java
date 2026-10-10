package com.sentinel.application.secret;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

import com.sentinel.application.Transactions;
import com.sentinel.domain.Environment;
import com.sentinel.domain.Project;
import com.sentinel.infra.persistence.EnvironmentRepository;
import com.sentinel.infra.persistence.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * 기동 시 저장된 암호문(환경 시크릿, 프로젝트 Webhook 주소)을 현재 키로 맞춤. 보관(삭제)된 항목 포함
 * <ul>
 * <li>이전 키({@code SENTINEL_SECRET_KEY_PREVIOUS})로 저장된 값은 현재 키로 다시 암호화</li>
 * <li>어느 키로도 풀 수 없는 값은 지우지 않고 위치만 경고. 올바른 이전 키로 다시 기동하면 복구 가능</li>
 * </ul>
 * 모든 빈 생성 직후, 요청 수신·스케줄 시작 전에 실행하여 화면에서의 수정과 겹치지 않음. 값은 로그에 남기지 않음
 */
@Component
@RequiredArgsConstructor
class SecretKeyRotation implements SmartInitializingSingleton {

	private static final Logger log = LoggerFactory.getLogger(SecretKeyRotation.class);

	private final SecretCipher cipher;

	private final ProjectRepository projects;

	private final EnvironmentRepository environments;

	private final Transactions transactions;

	@Override
	public void afterSingletonsInstantiated() {
		if (!cipher.isEnabled()) {
			return;
		}
		Result result = rotate();
		if (result.reEncrypted() > 0) {
			log.info("Re-encrypted {} secret value(s) saved with the previous key; SENTINEL_SECRET_KEY_PREVIOUS can be removed",
					result.reEncrypted());
		}
		if (!result.undecryptable().isEmpty()) {
			log.warn("{} secret value(s) cannot be decrypted with the configured key(s) and were left unchanged: {}. "
					+ "Set SENTINEL_SECRET_KEY_PREVIOUS to the key they were saved with, or enter them again",
					result.undecryptable().size(), String.join(", ", result.undecryptable()));
		}
	}

	Result rotate() {
		return transactions.write(status -> {
			List<String> undecryptable = new ArrayList<>();
			int reEncrypted = 0;
			for (Project project : projects.findAll()) {
				if (!project.hasWebhook()) {
					continue;
				}
				try {
					Optional<String> rotated = cipher.reEncrypt(project.getWebhookUrlCipher());
					if (rotated.isPresent()) {
						project.changeWebhook(rotated.get());
						reEncrypted++;
					}
				}
				catch (SecretDecryptionException ex) {
					undecryptable.add("project " + project.getId() + " webhook");
				}
			}
			for (Environment environment : environments.findAll()) {
				for (var secret : new LinkedHashMap<>(environment.getEncryptedSecrets()).entrySet()) {
					try {
						Optional<String> rotated = cipher.reEncrypt(secret.getValue());
						if (rotated.isPresent()) {
							environment.putSecret(secret.getKey(), rotated.get());
							reEncrypted++;
						}
					}
					catch (SecretDecryptionException ex) {
						undecryptable.add("environment " + environment.getId() + " secret '" + secret.getKey() + "'");
					}
				}
			}
			return new Result(reEncrypted, undecryptable);
		});
	}

	/**
	 * @param reEncrypted 이전 키에서 현재 키로 다시 암호화한 값 수
	 * @param undecryptable 어느 키로도 풀 수 없는 값의 위치 (예: {@code environment 3 secret 'token'})
	 */
	record Result(int reEncrypted, List<String> undecryptable) {
	}

}
