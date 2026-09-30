package com.sentinel.application.notification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookUrlsTest {

	@Test
	void acceptsAbsoluteHttpAndHttpsUrlsTrimmed() {
		assertThat(WebhookUrls.validate("  https://hooks.example.test/services/T1/B2  "))
			.isEqualTo("https://hooks.example.test/services/T1/B2");
		assertThat(WebhookUrls.validate("http://messenger.internal:8080/hook")).isEqualTo(
				"http://messenger.internal:8080/hook");
	}

	@ParameterizedTest
	@ValueSource(strings = { "ftp://hooks.example.test/x", "/relative/hook", "https:///no-host", "not a url",
			"mailto:someone@example.test" })
	void rejectsAnythingButAbsoluteHttpUrlsWithoutEchoingThem(String url) {
		assertThatThrownBy(() -> WebhookUrls.validate(url)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageStartingWith("webhookUrl must be")
			.message()
			.doesNotContain(url.strip());
	}

	@Test
	void limitsTheLength() {
		String base = "https://hooks.example.test/";
		assertThat(WebhookUrls.validate(base + "a".repeat(WebhookUrls.MAX_LENGTH - base.length()))).hasSize(1000);
		assertThatThrownBy(() -> WebhookUrls.validate(base + "a".repeat(WebhookUrls.MAX_LENGTH - base.length() + 1)))
			.hasMessage("webhookUrl must be at most 1000 characters");
	}

}
