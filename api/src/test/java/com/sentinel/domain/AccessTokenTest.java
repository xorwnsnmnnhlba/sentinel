package com.sentinel.domain;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccessTokenTest {

	private static final Instant NOW = Instant.parse("2026-09-30T00:00:00Z");

	private final AccessToken token = new AccessToken(1L, " CI ", "hash", "snt_Ab12", NOW, Duration.ofDays(30));

	@Test
	void isUsableUntilJustBeforeItExpires() {
		assertThat(token.getName()).isEqualTo("CI");
		assertThat(token.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(30)));
		assertThat(token.isUsable(NOW.plus(Duration.ofDays(30)).minusMillis(1))).isTrue();
		assertThat(token.isUsable(NOW.plus(Duration.ofDays(30)))).isFalse();
	}

	@Test
	void cannotBeUsedOrRevokedAgainOnceRevoked() {
		token.revoke(NOW);

		assertThat(token.isUsable(NOW)).isFalse();
		assertThatThrownBy(() -> token.revoke(NOW)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void requiresAName() {
		assertThatThrownBy(() -> new AccessToken(1L, " ", "hash", "snt_Ab12", NOW, Duration.ofDays(30)))
			.isInstanceOf(IllegalArgumentException.class);
	}

}
