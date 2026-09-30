package com.sentinel.application;

import com.sentinel.application.notification.NotificationChannels;
import com.sentinel.application.notification.WebhookSender;
import com.sentinel.application.secret.SecretDecryptionException;
import com.sentinel.application.secret.SecretKeyMissingException;
import com.sentinel.domain.Project;
import com.sentinel.domain.User;
import com.sentinel.infra.persistence.ProjectRepository;
import com.sentinel.infra.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 프로젝트 Webhook 연결 확인 알림. 발송은 트랜잭션 밖에서 (응답을 기다리는 동안 DB 커넥션을 점유하지 않도록)
 */
@Service
@RequiredArgsConstructor
public class ProjectNotificationService {

	private final ProjectRepository projects;

	private final UserRepository users;

	private final NotificationChannels channels;

	private final Transactions transactions;

	/**
	 * @param requesterId 보낸 사람. 알림에 이메일 표시
	 * @throws NotFoundException 프로젝트 미존재
	 * @throws ConflictException Webhook 미지정
	 * @throws InvalidRequestException 암호화 키 미설정·불일치로 주소를 읽을 수 없음
	 */
	public WebhookSender.Delivery sendTest(long projectId, long requesterId) {
		Loaded loaded = transactions.readOnly(status -> {
			Project project = projects.findByIdAndArchivedAtIsNull(projectId)
				.orElseThrow(() -> new NotFoundException("project", projectId));
			String requester = users.findById(requesterId).map(User::getEmail).orElse("알 수 없음");
			return new Loaded(project, requester);
		});
		try {
			return channels.sendTest(loaded.project(), loaded.requester());
		}
		catch (IllegalStateException ex) {
			throw new ConflictException(ex.getMessage());
		}
		catch (SecretKeyMissingException | SecretDecryptionException ex) {
			throw new InvalidRequestException("webhook of project " + projectId + " cannot be read: " + ex.getMessage());
		}
	}

	private record Loaded(Project project, String requester) {
	}

}
