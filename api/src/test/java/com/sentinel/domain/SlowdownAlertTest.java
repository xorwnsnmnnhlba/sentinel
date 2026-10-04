package com.sentinel.domain;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class SlowdownAlertTest {

	private static final Instant NOW = Instant.parse("2026-10-04T00:00:00Z");

	@Test
	void requiresASlowerRecentP95ThanTheBaseline() {
		assertThatIllegalArgumentException().isThrownBy(() -> new SlowdownAlert(1, 2, 0, 300, NOW));
		assertThatIllegalArgumentException().isThrownBy(() -> new SlowdownAlert(1, 2, 200, 200, NOW));
		assertThatIllegalArgumentException().isThrownBy(() -> new SlowdownAlert(1, 2, 200, 150, NOW));
		assertThat(new SlowdownAlert(1, 2, 200, 201, NOW).isOpen()).isTrue();
	}

	@Test
	void resolvesOnce() {
		SlowdownAlert alert = new SlowdownAlert(1, 2, 200, 350, NOW);

		alert.resolve(220, NOW.plusSeconds(3600));

		assertThat(alert.isOpen()).isFalse();
		assertThat(alert.getResolvedP95()).isEqualTo(220);
		assertThatIllegalStateException().isThrownBy(() -> alert.resolve(210, NOW.plusSeconds(7200)));
	}

}
