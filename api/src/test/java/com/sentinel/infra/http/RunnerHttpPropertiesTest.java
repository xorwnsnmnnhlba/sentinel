package com.sentinel.infra.http;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunnerHttpPropertiesTest {

	@Test
	void rejectsANonPositiveTotalTimeout() {
		assertThatThrownBy(() -> new RunnerHttpProperties(Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ZERO,
				DataSize.ofMegabytes(10)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("total-timeout");
	}

	@Test
	void rejectsAResponseSizeOutsideOneByteToTwoGigabytes() {
		assertThatThrownBy(() -> new RunnerHttpProperties(Duration.ofSeconds(5), Duration.ofSeconds(30),
				Duration.ofMinutes(2), DataSize.ofBytes(0)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("max-response-size");
		assertThatThrownBy(() -> new RunnerHttpProperties(Duration.ofSeconds(5), Duration.ofSeconds(30),
				Duration.ofMinutes(2), DataSize.ofGigabytes(3)))
			.isInstanceOf(IllegalArgumentException.class);
	}

}
