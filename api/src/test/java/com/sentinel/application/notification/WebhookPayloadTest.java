package com.sentinel.application.notification;

import java.util.List;
import java.util.Map;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Webhook 주소별 본문 서식. 실제 Slack·Teams로 보내지 않고 JSON 구조로 확인 (Block Kit·Adaptive Card 필드)
 */
class WebhookPayloadTest {

	private static final Notification FAILURE = new Notification("[Sentinel] dev 결제 - 12건 중 1건 실패 (새로 실패 1건)",
			List.of("[새로 실패] 결제 승인 · POST /pay → status in [2xx]: was 500", "계속 실패하면 1시간마다 다시 알림 (새로 실패·복구는 바로)"),
			Notification.Tone.FAILURE, List.of(new Notification.Link("결과", "https://sentinel.example.com/runs/12")));

	@Test
	void picksTheFormatFromTheAddress() {
		assertThat(WebhookPayload.formatOf("https://hooks.slack.com/services/T0/B0/xyz"))
			.isEqualTo(WebhookPayload.Format.SLACK);
		assertThat(WebhookPayload.formatOf("https://HOOKS.SLACK.COM/services/T0/B0/xyz"))
			.isEqualTo(WebhookPayload.Format.SLACK);
		assertThat(WebhookPayload.formatOf("https://prod-12.koreacentral.logic.azure.com:443/workflows/abc/triggers"))
			.isEqualTo(WebhookPayload.Format.TEAMS);
		assertThat(WebhookPayload.formatOf("https://default123.environment.api.powerplatform.com/powerautomate/x"))
			.isEqualTo(WebhookPayload.Format.TEAMS);
		assertThat(WebhookPayload.formatOf("https://company.webhook.office.com/webhookb2/abc"))
			.isEqualTo(WebhookPayload.Format.TEAMS);
		// 비슷하지만 다른 주소, 사내 중계, 잘못된 주소는 공통 텍스트
		assertThat(WebhookPayload.formatOf("https://hooks.slack.com.evil.example/x")).isEqualTo(WebhookPayload.Format.TEXT);
		assertThat(WebhookPayload.formatOf("https://notslack-hooks.slack.com/x")).isEqualTo(WebhookPayload.Format.TEXT);
		assertThat(WebhookPayload.formatOf("https://webhook.office.com.example/x")).isEqualTo(WebhookPayload.Format.TEXT);
		assertThat(WebhookPayload.formatOf("https://a.webhook.office.com.example/x")).isEqualTo(WebhookPayload.Format.TEXT);
		assertThat(WebhookPayload.formatOf("https://a.logic.azure.com.example/x")).isEqualTo(WebhookPayload.Format.TEXT);
		assertThat(WebhookPayload.formatOf("https://a.powerplatform.com.example/x")).isEqualTo(WebhookPayload.Format.TEXT);
		assertThat(WebhookPayload.formatOf("http://10.0.0.5:8080/relay")).isEqualTo(WebhookPayload.Format.TEXT);
		assertThat(WebhookPayload.formatOf("not a url")).isEqualTo(WebhookPayload.Format.TEXT);
	}

	@Test
	void keepsThePlainTextForOtherChannels() {
		assertThat(WebhookPayload.of("http://10.0.0.5:8080/relay", FAILURE)).isEqualTo(Map.of("text",
				"[Sentinel] dev 결제 - 12건 중 1건 실패 (새로 실패 1건)\n[새로 실패] 결제 승인 · POST /pay → status in [2xx]: was 500\n"
						+ "계속 실패하면 1시간마다 다시 알림 (새로 실패·복구는 바로)\n결과: https://sentinel.example.com/runs/12"));
	}

	@Test
	void buildsASlackMessageWithAColorBarHeaderBodyAndButtons() {
		String json = json(WebhookPayload.of("https://hooks.slack.com/services/T0/B0/xyz", FAILURE));

		// 알림 미리보기용
		assertThat(read(json, "$.text")).isEqualTo(FAILURE.title());
		assertThat(read(json, "$.attachments[0].color")).isEqualTo("#d03b3b");
		assertThat(read(json, "$.attachments[0].blocks[0].type")).isEqualTo("header");
		assertThat(read(json, "$.attachments[0].blocks[0].text.text")).isEqualTo(FAILURE.title());
		// 본문은 서식 문법으로 해석되지 않도록 plain_text
		assertThat(read(json, "$.attachments[0].blocks[1].text.type")).isEqualTo("plain_text");
		assertThat(read(json, "$.attachments[0].blocks[1].text.text"))
			.isEqualTo(String.join("\n", FAILURE.lines()));
		assertThat(read(json, "$.attachments[0].blocks[2].type")).isEqualTo("actions");
		assertThat(read(json, "$.attachments[0].blocks[2].elements[0].text.text")).isEqualTo("결과 보기");
		assertThat(read(json, "$.attachments[0].blocks[2].elements[0].url"))
			.isEqualTo("https://sentinel.example.com/runs/12");
	}

	@Test
	void colorsSlackByToneAndLeavesOutEmptyParts() {
		Map<String, String> colors = Map.of("SUCCESS", "#2e9d4e", "WARNING", "#e8a33d", "INFO", "#8f97a1");
		colors.forEach((tone, color) -> {
			Notification notification = new Notification("제목", List.of(), Notification.Tone.valueOf(tone), List.of());
			String json = json(WebhookPayload.slack(notification));
			assertThat(read(json, "$.attachments[0].color")).isEqualTo(color);
			// 본문·링크가 없으면 머리만
			assertThat(JsonPath.<List<Object>>read(json, "$.attachments[0].blocks")).hasSize(1);
		});
	}

	@Test
	void cutsSlackTextToTheBlockLimits() {
		Notification longOne = new Notification("가".repeat(200), List.of("나".repeat(4000)), Notification.Tone.INFO,
				List.of());
		String json = json(WebhookPayload.slack(longOne));

		assertThat(read(json, "$.attachments[0].blocks[0].text.text")).hasSize(WebhookPayload.SLACK_HEADER_MAX)
			.endsWith("…");
		assertThat(read(json, "$.attachments[0].blocks[1].text.text")).hasSize(WebhookPayload.SLACK_SECTION_MAX)
			.endsWith("…");
	}

	@Test
	void keepsSlackTextThatFitsTheLimit() {
		Notification exact = new Notification("가".repeat(WebhookPayload.SLACK_HEADER_MAX), List.of(), Notification.Tone.INFO,
				List.of());

		assertThat(read(json(WebhookPayload.slack(exact)), "$.attachments[0].blocks[0].text.text")).isEqualTo(exact.title());
	}

	@Test
	void buildsATeamsAdaptiveCard() {
		String json = json(WebhookPayload.of("https://prod-12.koreacentral.logic.azure.com/workflows/abc", FAILURE));

		assertThat(read(json, "$.type")).isEqualTo("message");
		assertThat(read(json, "$.text")).isEqualTo(FAILURE.title());
		assertThat(read(json, "$.attachments[0].contentType")).isEqualTo("application/vnd.microsoft.card.adaptive");
		String card = "$.attachments[0].content";
		assertThat(read(json, card + ".type")).isEqualTo("AdaptiveCard");
		assertThat(read(json, card + ".version")).isEqualTo("1.4");
		assertThat(read(json, card + ".body[0].text")).isEqualTo(FAILURE.title());
		assertThat(read(json, card + ".body[0].weight")).isEqualTo("Bolder");
		assertThat(read(json, card + ".body[0].color")).isEqualTo("Attention");
		// 줄마다 TextBlock (줄바꿈이 합쳐지지 않도록)
		assertThat(JsonPath.<List<String>>read(json, card + ".body[1:].text")).containsExactlyElementsOf(FAILURE.lines());
		assertThat(read(json, card + ".actions[0].type")).isEqualTo("Action.OpenUrl");
		assertThat(read(json, card + ".actions[0].title")).isEqualTo("결과 보기");
		assertThat(read(json, card + ".actions[0].url")).isEqualTo("https://sentinel.example.com/runs/12");
	}

	@Test
	void colorsTeamsByToneAndLeavesOutActionsWithoutLinks() {
		Map<String, String> colors = Map.of("SUCCESS", "Good", "WARNING", "Warning", "INFO", "Default");
		colors.forEach((tone, color) -> {
			String json = json(WebhookPayload
				.teams(new Notification("제목", List.of(), Notification.Tone.valueOf(tone), List.of())));
			assertThat(read(json, "$.attachments[0].content.body[0].color")).isEqualTo(color);
			assertThat(JsonPath.<Map<String, Object>>read(json, "$.attachments[0].content")).doesNotContainKey("actions");
		});
	}

	private static String json(Object payload) {
		return JsonMapper.builder().build().writeValueAsString(payload);
	}

	private static String read(String json, String path) {
		return JsonPath.read(json, path);
	}

}
