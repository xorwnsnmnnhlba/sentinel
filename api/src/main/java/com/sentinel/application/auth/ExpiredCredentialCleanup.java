package com.sentinel.application.auth;

import java.time.Clock;
import java.time.Instant;

import com.sentinel.infra.persistence.UserSessionRepository;
import com.sentinel.infra.persistence.UserTokenRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 만료된 로그인 세션과 초대·재설정 링크 삭제 (보관 배치와 같은 시각, {@code sentinel.retention.cron}).
 * <p>
 * 만료되면 인증·링크 확인에 쓰이지 않아 지워도 동작은 같음. 쓸모없는 행과 해시가 쌓이지 않도록 정리.
 * 만료된 세션은 다시 쓰면 인증에서 바로 지우므로, 대부분 브라우저를 닫고 다시 오지 않은 세션이 대상
 * 사용한 링크도 만료 시각이 지나면 삭제 (발급·가입 기록은 변경 이력에 남음). API 토큰은 화면의 발급 목록이므로 대상 아님
 */
@Component
@RequiredArgsConstructor
public class ExpiredCredentialCleanup {

	private static final Logger log = LoggerFactory.getLogger(ExpiredCredentialCleanup.class);

	private final UserSessionRepository sessions;

	private final UserTokenRepository tokens;

	private final Clock clock;

	@Transactional
	public Deleted deleteExpired() {
		Instant now = clock.instant();
		Deleted deleted = new Deleted(sessions.deleteExpired(now), tokens.deleteExpired(now));
		if (deleted.sessions() > 0 || deleted.links() > 0) {
			log.info("Deleted {} expired session(s) and {} expired invite/password reset link(s)", deleted.sessions(),
					deleted.links());
		}
		return deleted;
	}

	/**
	 * @param sessions 삭제한 로그인 세션 수
	 * @param links 삭제한 초대·재설정 링크 수
	 */
	public record Deleted(int sessions, int links) {
	}

}
