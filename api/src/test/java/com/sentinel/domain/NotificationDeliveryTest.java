package com.sentinel.domain;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationDeliveryTest {

	private static final Instant NOW = Instant.parse("2026-10-05T00:00:00Z");

	@Test
	void cutsLongTitlesAndErrors() {
		NotificationDelivery delivery = delivery(NotificationDelivery.Channel.DEFAULT, null, "가".repeat(301),
				"E".repeat(41));

		assertThat(delivery.getTitle()).hasSize(300).endsWith("가…");
		assertThat(delivery.getError()).hasSize(40).endsWith("E…");
		assertThat(delivery(NotificationDelivery.Channel.DEFAULT, null, "가".repeat(300), "E".repeat(40)).getTitle())
			.isEqualTo("가".repeat(300));
		assertThat(delivery(NotificationDelivery.Channel.DEFAULT, null, "알림", null).getError()).isNull();
	}

	@Test
	void keepsTheProjectOnlyForProjectWebhooks() {
		assertThat(delivery(NotificationDelivery.Channel.PROJECT, 3L, "알림", null).getProjectId()).isEqualTo(3L);
		assertThatThrownBy(() -> delivery(NotificationDelivery.Channel.PROJECT, null, "알림", null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> delivery(NotificationDelivery.Channel.DEFAULT, 3L, "알림", null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	private static NotificationDelivery delivery(NotificationDelivery.Channel channel, Long projectId, String title,
			String error) {
		return new NotificationDelivery(NOW, channel, projectId, NotificationDelivery.Format.TEXT, title,
				NotificationDelivery.Tone.INFO, false, null, error);
	}

}
