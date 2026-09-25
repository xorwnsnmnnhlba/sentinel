package com.sentinel.application.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * 반복된 로그인 실패 제한 (비밀번호 대입 방지). 이메일별로 {@link #WINDOW} 안에 {@link #MAX_FAILURES}회 실패하면
 * {@link #LOCK} 동안 올바른 비밀번호로도 로그인 거부.
 * <p>
 * <ul>
 * <li>없는 계정도 같은 방식으로 세어 잠김 여부로 계정 존재를 알 수 없음</li>
 * <li>IP 기준은 쓰지 않음. Spring에는 Next.js 서버 주소만 보여 사용자를 구분할 수 없음</li>
 * <li>다른 사람이 특정 계정을 일부러 잠글 수 있음 (최대 {@link #LOCK}). 대입 방지와 맞바꾼 제약</li>
 * <li>메모리 보관이라 재시작하면 초기화. 단일 인스턴스 전제</li>
 * </ul>
 */
@Component
public class LoginThrottle {

	static final int MAX_FAILURES = 5;

	static final Duration WINDOW = Duration.ofMinutes(15);

	static final Duration LOCK = Duration.ofMinutes(15);

	/** 이 수를 넘으면 기한이 지난 기록 정리 */
	private static final int PRUNE_THRESHOLD = 10_000;

	private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();

	/**
	 * @throws TooManyLoginAttemptsException 잠긴 이메일
	 */
	void checkAllowed(String email, Instant now) {
		Attempts current = attempts.get(email);
		if (current != null && current.lockedUntil() != null && now.isBefore(current.lockedUntil())) {
			throw new TooManyLoginAttemptsException(Duration.between(now, current.lockedUntil()));
		}
	}

	void recordFailure(String email, Instant now) {
		attempts.compute(email, (key, previous) -> {
			boolean fresh = previous == null || !now.isBefore(previous.windowStart().plus(WINDOW))
					|| (previous.lockedUntil() != null && !now.isBefore(previous.lockedUntil()));
			int failures = fresh ? 1 : previous.failures() + 1;
			Instant windowStart = fresh ? now : previous.windowStart();
			return new Attempts(failures, windowStart, failures >= MAX_FAILURES ? now.plus(LOCK) : null);
		});
		if (attempts.size() > PRUNE_THRESHOLD) {
			attempts.values().removeIf(entry -> entry.expired(now));
		}
	}

	void recordSuccess(String email) {
		attempts.remove(email);
	}

	/**
	 * @param lockedUntil 잠김 해제 시각. 잠기지 않았으면 null
	 */
	private record Attempts(int failures, Instant windowStart, Instant lockedUntil) {

		boolean expired(Instant now) {
			Instant end = lockedUntil != null ? lockedUntil : windowStart.plus(WINDOW);
			return !now.isBefore(end);
		}

	}

}
