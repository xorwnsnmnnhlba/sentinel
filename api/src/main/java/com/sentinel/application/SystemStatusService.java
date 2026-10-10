package com.sentinel.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.sentinel.application.notification.NotificationProperties;
import com.sentinel.application.secret.SecretCipher;
import com.sentinel.application.secret.SecretDecryptionException;
import com.sentinel.domain.Environment;
import com.sentinel.domain.Project;
import com.sentinel.infra.persistence.EnvironmentRepository;
import com.sentinel.infra.persistence.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * 서버 설정·버전 상태 (관리자 화면). 배포·키 교체 후 서버 로그 대신 화면에서 확인하도록.
 * 주소·키 같은 설정 값은 내보내지 않고 지정 여부만
 */
@Service
@RequiredArgsConstructor
public class SystemStatusService {

	private final ObjectProvider<BuildProperties> buildProperties;

	private final Flyway flyway;

	private final SecretCipher cipher;

	private final ProjectRepository projects;

	private final EnvironmentRepository environments;

	private final NotificationProperties notification;

	private final HeartbeatProperties heartbeat;

	private final RetentionProperties retention;

	private final Transactions transactions;

	private final Clock clock;

	private volatile Instant startedAt;

	@EventListener(ApplicationReadyEvent.class)
	void started() {
		startedAt = clock.instant();
	}

	public SystemStatus status() {
		BuildProperties build = buildProperties.getIfAvailable();
		MigrationInfo migration = flyway.info().current();
		return new SystemStatus(build == null ? null : new Build(build.getVersion(), build.getTime()), startedAt,
				migration == null ? null : migration.getVersion().getVersion(), secrets(), notification.hasWebhook(),
				notification.dashboardUrl() != null, heartbeat.enabled(),
				new Retention(retention.rawRetention().toDays(), retention.notificationRetention().toDays()));
	}

	private Secrets secrets() {
		if (!cipher.isEnabled()) {
			return new Secrets(SecretState.DISABLED, List.of());
		}
		return new Secrets(cipher.hasPreviousKey() ? SecretState.ROTATING : SecretState.ENABLED,
				transactions.readOnly(status -> undecryptable()));
	}

	/** 사용 중인 항목만 (보관된 항목은 화면에서 고칠 수 없음). 프로젝트 이름 순, 프로젝트 Webhook 먼저 */
	private List<UndecryptableSecret> undecryptable() {
		List<UndecryptableSecret> found = new ArrayList<>();
		for (Project project : projects.findByArchivedAtIsNullOrderByName()) {
			if (project.hasWebhook() && !decrypts(project.getWebhookUrlCipher())) {
				found.add(new UndecryptableSecret(IdName.of(project), null, null));
			}
		}
		// 프로젝트는 위에서 읽어 영속성 컨텍스트에 있으므로 환경마다 추가 조회 없음
		for (Environment environment : environments.findByArchivedAtIsNullOrderByName()) {
			environment.getEncryptedSecrets().forEach((name, cipherText) -> {
				if (!decrypts(cipherText)) {
					found.add(new UndecryptableSecret(IdName.of(environment.getProject()), IdName.of(environment),
							name));
				}
			});
		}
		return found;
	}

	private boolean decrypts(String cipherText) {
		try {
			cipher.decrypt(cipherText);
			return true;
		}
		catch (SecretDecryptionException ex) {
			return false;
		}
	}

	/**
	 * @param build 실행 중인 빌드. 빌드 정보 없이 실행하면(IDE 등) null
	 * @param startedAt 기동 완료 시각
	 * @param databaseMigration 적용된 마지막 DB 마이그레이션 버전 (Flyway, 예: {@code 29})
	 * @param secrets 시크릿 암호화 상태
	 * @param defaultChannel 기본 알림 채널 주소({@code SENTINEL_WEBHOOK_URL}) 지정 여부. 없으면 알림을 로그로만
	 * @param resultLinks 결과 화면 주소({@code SENTINEL_WEB_URL}) 지정 여부. 없으면 알림에 결과 링크 없음
	 * @param heartbeat 외부 생존 신호 주소({@code SENTINEL_HEARTBEAT_URL}) 지정 여부
	 * @param retention 보관 기간
	 */
	public record SystemStatus(Build build, Instant startedAt, String databaseMigration, Secrets secrets,
			boolean defaultChannel, boolean resultLinks, boolean heartbeat, Retention retention) {
	}

	/**
	 * @param version 빌드 버전
	 * @param builtAt 빌드 시각. 업데이트가 반영됐는지 확인용
	 */
	public record Build(String version, Instant builtAt) {
	}

	/**
	 * @param state 암호화 키 상태
	 * @param undecryptable 지금 키로 풀 수 없는 값 (키 변경·분실). 화면에서 다시 입력하거나 이전 키를 지정해 재기동
	 */
	public record Secrets(SecretState state, List<UndecryptableSecret> undecryptable) {
	}

	/** 암호화 키 상태 */
	public enum SecretState {

		/** 키 없음. 시크릿·프로젝트 Webhook 등록 불가 */
		DISABLED,
		/** 사용 중 */
		ENABLED,
		/** 키 교체 중 (이전 키 설정). 기동 시 재암호화 후 이전 키 제거 */
		ROTATING

	}

	/**
	 * @param project 프로젝트
	 * @param environment 환경. 프로젝트 Webhook 주소면 null
	 * @param secret 시크릿 이름. 프로젝트 Webhook 주소면 null
	 */
	public record UndecryptableSecret(IdName project, IdName environment, String secret) {
	}

	/**
	 * @param rawResultDays 실행 결과의 요청·응답 원문 보관 일수
	 * @param notificationDeliveryDays 알림 발송 이력 보관 일수
	 */
	public record Retention(long rawResultDays, long notificationDeliveryDays) {
	}

}
