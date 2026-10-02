package com.sentinel.domain;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class MonitoringGapTest {

	private static final Instant NINE = Instant.parse("2026-10-02T00:00:00Z");

	private static final Instant TEN = Instant.parse("2026-10-02T01:00:00Z");

	private static final Duration TEN_MINUTES = Duration.ofMinutes(10);

	@Test
	void rejectsEmptyPeriodAndNoSchedules() {
		assertThatIllegalArgumentException().isThrownBy(() -> new MonitoringGap(TEN, NINE, 1));
		assertThatIllegalArgumentException().isThrownBy(() -> new MonitoringGap(NINE, NINE, 1));
		assertThatIllegalArgumentException().isThrownBy(() -> new MonitoringGap(NINE, TEN, 0));
		assertThat(new MonitoringGap(NINE, TEN, 1).duration()).isEqualTo(Duration.ofHours(1));
	}

	@Test
	void isContinuedByGapStartingWithinToleranceAfterItsEnd() {
		MonitoringGap gap = new MonitoringGap(NINE, TEN, 1);

		assertThat(gap.isContinuedBy(TEN.minus(TEN_MINUTES), TEN_MINUTES)).isTrue();
		assertThat(gap.isContinuedBy(TEN.plus(TEN_MINUTES), TEN_MINUTES)).isTrue();
		assertThat(gap.isContinuedBy(TEN.plus(TEN_MINUTES).plusSeconds(1), TEN_MINUTES)).isFalse();
	}

	@Test
	void extendsToTheWiderPeriodAndTheLargerScheduleCount() {
		MonitoringGap gap = new MonitoringGap(NINE, TEN, 3);

		gap.extend(TEN.plusSeconds(600), TEN.plusSeconds(1500), 1);

		assertThat(gap.getStartedAt()).isEqualTo(NINE);
		assertThat(gap.getEndedAt()).isEqualTo(TEN.plusSeconds(1500));
		assertThat(gap.getMissedSchedules()).isEqualTo(3);

		gap.extend(NINE.minusSeconds(60), NINE.plusSeconds(60), 5);

		assertThat(gap.getStartedAt()).isEqualTo(NINE.minusSeconds(60));
		assertThat(gap.getEndedAt()).isEqualTo(TEN.plusSeconds(1500));
		assertThat(gap.getMissedSchedules()).isEqualTo(5);
		assertThatIllegalArgumentException().isThrownBy(() -> gap.extend(TEN, NINE, 1));
		assertThatIllegalArgumentException().isThrownBy(() -> gap.extend(NINE, TEN, 0));
	}

}
