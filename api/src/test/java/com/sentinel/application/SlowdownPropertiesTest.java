package com.sentinel.application;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class SlowdownPropertiesTest {

	private final SlowdownProperties defaults = properties(0.5, Duration.ofMillis(100), 5);

	@Test
	void isSlowOnlyWhenBothTheRatioAndTheIncreaseReachTheThresholds() {
		assertThat(defaults.isSlow(200, 300)).isTrue();
		assertThat(defaults.isSlow(200, 299)).isFalse();
		// 1.5배지만 +99ms
		assertThat(defaults.isSlow(198, 297)).isFalse();
		assertThat(defaults.isSlow(200, 299)).isFalse();
		// +100ms이지만 1.5배 미만
		assertThat(defaults.isSlow(1000, 1100)).isFalse();
		assertThat(defaults.isSlow(1000, 1500)).isTrue();
	}

	@Test
	void recoversBelowHalfOfTheThresholds() {
		// 기준 200ms: 1.25배(250ms) 미만 또는 증가 50ms 미만이면 회복
		assertThat(defaults.isRecovered(200, 249)).isTrue();
		assertThat(defaults.isRecovered(200, 250)).isFalse();
		assertThat(defaults.isRecovered(1000, 1049)).isTrue();
		assertThat(defaults.isRecovered(1000, 1300)).isFalse();
		// 둘 중 하나만 만족해도 회복: 비율만(+100ms지만 1.1배), 증가폭만(1.98배지만 +49ms)
		assertThat(defaults.isRecovered(1000, 1100)).isTrue();
		assertThat(defaults.isRecovered(50, 99)).isTrue();
		assertThat(defaults.isRecovered(50, 100)).isFalse();
		// 악화 기준과 회복 기준 사이는 그대로
		assertThat(defaults.isSlow(200, 280)).isFalse();
		assertThat(defaults.isRecovered(200, 280)).isFalse();
	}

	@Test
	void rejectsInvalidSettings() {
		assertThatIllegalArgumentException().isThrownBy(() -> properties(0, Duration.ofMillis(100), 5));
		assertThatIllegalArgumentException().isThrownBy(() -> properties(Double.NaN, Duration.ofMillis(100), 5));
		assertThatIllegalArgumentException().isThrownBy(() -> properties(0.5, Duration.ofMillis(-1), 5));
		assertThatIllegalArgumentException().isThrownBy(() -> properties(0.5, Duration.ofMillis(100), 0));
		assertThatIllegalArgumentException().isThrownBy(() -> new SlowdownProperties(true, "0 5 * * *", 0.5,
				Duration.ofMillis(100), Duration.ofHours(24), Duration.ofDays(7), 5));
		assertThatIllegalArgumentException().isThrownBy(() -> new SlowdownProperties(true, "0 5 * * * *", 0.5,
				Duration.ofMillis(100), Duration.ZERO, Duration.ofDays(7), 5));
	}

	private static SlowdownProperties properties(double ratio, Duration minIncrease, int minSamples) {
		return new SlowdownProperties(true, "0 5 * * * *", ratio, minIncrease, Duration.ofHours(24), Duration.ofDays(7),
				minSamples);
	}

}
