package com.sentinel.application;

import java.time.Instant;
import java.time.LocalDate;

import com.sentinel.domain.DigestDelivery;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;

class DigestPropertiesTest {

	@Test
	void rejectsInvalidCronAtStartup() {
		assertThatNoException().isThrownBy(() -> new DigestProperties(true, "0 0 9 * * MON"));
		// 스케줄과 달리 초 포함 6자리 (정리 배치와 같은 형식)
		assertThatIllegalArgumentException().isThrownBy(() -> new DigestProperties(true, "0 9 * * MON"))
			.withMessageContaining("sentinel.digest.cron");
	}

	@Test
	void rejectsDeliveryWithReversedPeriod() {
		assertThatIllegalArgumentException().isThrownBy(() -> new DigestDelivery(Instant.parse("2026-08-17T00:00:00Z"),
				LocalDate.of(2026, 8, 16), LocalDate.of(2026, 8, 10)));
		assertThatNoException().isThrownBy(() -> new DigestDelivery(Instant.parse("2026-08-17T00:00:00Z"),
				LocalDate.of(2026, 8, 16), LocalDate.of(2026, 8, 16)));
	}

}
