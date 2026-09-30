package com.sentinel.application.notification;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * 프로젝트 Webhook 주소 확인. http(s) 절대 주소만
 */
public final class WebhookUrls {

	public static final int MAX_LENGTH = 1000;

	private WebhookUrls() {
	}

	/**
	 * @return 앞뒤 공백을 뺀 주소
	 * @throws IllegalArgumentException http(s) 절대 주소가 아니거나 너무 긺. 메시지에 주소를 넣지 않음 (자격증명)
	 */
	public static String validate(String url) {
		String trimmed = url.strip();
		if (trimmed.length() > MAX_LENGTH) {
			throw new IllegalArgumentException("webhookUrl must be at most " + MAX_LENGTH + " characters");
		}
		URI uri;
		try {
			uri = new URI(trimmed);
		}
		catch (URISyntaxException ex) {
			throw new IllegalArgumentException("webhookUrl must be a valid URL");
		}
		if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
				|| uri.getHost() == null) {
			throw new IllegalArgumentException("webhookUrl must be an absolute http(s) URL");
		}
		return trimmed;
	}

}
