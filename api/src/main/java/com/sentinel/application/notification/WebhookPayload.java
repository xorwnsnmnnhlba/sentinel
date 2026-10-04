package com.sentinel.application.notification;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Webhook 본문. 주소로 채널을 판별하여 채널 서식으로, 모르는 채널은 공통 텍스트({@code {"text": "..."}})
 * <ul>
 * <li>Slack(hooks.slack.com): Block Kit. 굵은 제목 머리, 본문, 링크 버튼. 성격 색 막대(attachments)</li>
 * <li>Teams(워크플로 *.logic.azure.com·*.powerplatform.com, 기존 커넥터 *.webhook.office.com): Adaptive Card. 제목 색, 줄마다
 * TextBlock, 링크는 Action.OpenUrl</li>
 * </ul>
 * 두 서식 모두 알림 미리보기용 {@code text}(제목)를 함께 보냄. 본문은 서식 문법으로 해석되지 않도록 Slack은 plain_text로 보냄
 */
final class WebhookPayload {

	/** Slack 머리 블록 최대 글자 수 */
	static final int SLACK_HEADER_MAX = 150;

	/** Slack 본문 블록 최대 글자 수 */
	static final int SLACK_SECTION_MAX = 3000;

	private WebhookPayload() {
	}

	enum Format {
		TEXT, SLACK, TEAMS
	}

	static Format formatOf(String url) {
		String host;
		try {
			host = URI.create(url).getHost();
		}
		catch (IllegalArgumentException ex) {
			return Format.TEXT;
		}
		if (host == null) {
			return Format.TEXT;
		}
		host = host.toLowerCase(Locale.ROOT);
		if (host.equals("hooks.slack.com")) {
			return Format.SLACK;
		}
		if (host.endsWith(".webhook.office.com") || host.endsWith(".logic.azure.com")
				|| host.endsWith(".powerplatform.com")) {
			return Format.TEAMS;
		}
		return Format.TEXT;
	}

	static Map<String, Object> of(String url, Notification notification) {
		return switch (formatOf(url)) {
			case SLACK -> slack(notification);
			case TEAMS -> teams(notification);
			case TEXT -> Map.of("text", notification.text());
		};
	}

	static Map<String, Object> slack(Notification notification) {
		List<Object> blocks = new ArrayList<>();
		blocks.add(Map.of("type", "header", "text", plain(cut(notification.title(), SLACK_HEADER_MAX))));
		if (!notification.lines().isEmpty()) {
			blocks.add(Map.of("type", "section", "text",
					plain(cut(String.join("\n", notification.lines()), SLACK_SECTION_MAX))));
		}
		if (!notification.links().isEmpty()) {
			blocks.add(Map.of("type", "actions", "elements",
					notification.links()
						.stream()
						.map(link -> Map.of("type", "button", "text", plain(link.label() + " 보기"), "url", link.url()))
						.toList()));
		}
		Map<String, Object> attachment = new LinkedHashMap<>();
		attachment.put("color", slackColor(notification.tone()));
		attachment.put("blocks", blocks);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("text", notification.title());
		body.put("attachments", List.of(attachment));
		return body;
	}

	static Map<String, Object> teams(Notification notification) {
		List<Object> blocks = new ArrayList<>();
		Map<String, Object> title = new LinkedHashMap<>();
		title.put("type", "TextBlock");
		title.put("text", notification.title());
		title.put("weight", "Bolder");
		title.put("size", "Medium");
		title.put("color", teamsColor(notification.tone()));
		title.put("wrap", true);
		blocks.add(title);
		notification.lines()
			.forEach(line -> blocks.add(Map.of("type", "TextBlock", "text", line, "wrap", true, "spacing", "Small")));
		Map<String, Object> card = new LinkedHashMap<>();
		card.put("$schema", "http://adaptivecards.io/schemas/adaptive-card.json");
		card.put("type", "AdaptiveCard");
		card.put("version", "1.4");
		card.put("body", blocks);
		if (!notification.links().isEmpty()) {
			card.put("actions",
					notification.links()
						.stream()
						.map(link -> Map.of("type", "Action.OpenUrl", "title", link.label() + " 보기", "url", link.url()))
						.toList());
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("type", "message");
		body.put("text", notification.title());
		body.put("attachments",
				List.of(Map.of("contentType", "application/vnd.microsoft.card.adaptive", "content", card)));
		return body;
	}

	private static Map<String, Object> plain(String text) {
		return Map.of("type", "plain_text", "text", text);
	}

	private static String cut(String text, int max) {
		return text.length() <= max ? text : text.substring(0, max - 1) + "…";
	}

	private static String slackColor(Notification.Tone tone) {
		return switch (tone) {
			case FAILURE -> "#d03b3b";
			case WARNING -> "#e8a33d";
			case SUCCESS -> "#2e9d4e";
			case INFO -> "#8f97a1";
		};
	}

	private static String teamsColor(Notification.Tone tone) {
		return switch (tone) {
			case FAILURE -> "Attention";
			case WARNING -> "Warning";
			case SUCCESS -> "Good";
			case INFO -> "Default";
		};
	}

}
