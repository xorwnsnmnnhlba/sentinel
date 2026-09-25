package com.sentinel.application.auth;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoginThrottleTest {

	private static final Instant NOW = Instant.parse("2026-09-24T00:00:00Z");

	private final LoginThrottle throttle = new LoginThrottle();

	@Test
	void locksAfterTooManyFailuresWithinTheWindow() {
		for (int i = 0; i < LoginThrottle.MAX_FAILURES - 1; i++) {
			throttle.recordFailure("a@x.io", NOW.plusSeconds(i));
		}
		assertThatCode(() -> throttle.checkAllowed("a@x.io", NOW.plusSeconds(10))).doesNotThrowAnyException();

		throttle.recordFailure("a@x.io", NOW.plusSeconds(10));

		assertThatThrownBy(() -> throttle.checkAllowed("a@x.io", NOW.plusSeconds(70)))
			.isInstanceOfSatisfying(TooManyLoginAttemptsException.class,
					ex -> assertThat(ex.retryAfter()).isEqualTo(LoginThrottle.LOCK.minusSeconds(60)));
		// 다른 이메일은 영향 없음
		assertThatCode(() -> throttle.checkAllowed("b@x.io", NOW.plusSeconds(70))).doesNotThrowAnyException();
	}

	@Test
	void unlocksAfterTheLockAndStartsCountingAgain() {
		for (int i = 0; i < LoginThrottle.MAX_FAILURES; i++) {
			throttle.recordFailure("a@x.io", NOW);
		}
		Instant unlocked = NOW.plus(LoginThrottle.LOCK);

		assertThatCode(() -> throttle.checkAllowed("a@x.io", unlocked)).doesNotThrowAnyException();
		// 잠김이 풀린 뒤 실패 1회로 다시 잠기지 않음
		throttle.recordFailure("a@x.io", unlocked);
		assertThatCode(() -> throttle.checkAllowed("a@x.io", unlocked)).doesNotThrowAnyException();
	}

	@Test
	void forgetsFailuresOutsideTheWindowAndAfterSuccess() {
		for (int i = 0; i < LoginThrottle.MAX_FAILURES - 1; i++) {
			throttle.recordFailure("a@x.io", NOW);
		}
		// 기간이 지난 뒤의 실패는 새로 셈
		throttle.recordFailure("a@x.io", NOW.plus(LoginThrottle.WINDOW));
		assertThatCode(() -> throttle.checkAllowed("a@x.io", NOW.plus(LoginThrottle.WINDOW)))
			.doesNotThrowAnyException();

		for (int i = 0; i < LoginThrottle.MAX_FAILURES - 1; i++) {
			throttle.recordFailure("b@x.io", NOW);
		}
		throttle.recordSuccess("b@x.io");
		throttle.recordFailure("b@x.io", NOW.plus(Duration.ofMinutes(1)));
		assertThatCode(() -> throttle.checkAllowed("b@x.io", NOW.plus(Duration.ofMinutes(1))))
			.doesNotThrowAnyException();
	}

}
