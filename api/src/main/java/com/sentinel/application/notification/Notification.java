package com.sentinel.application.notification;

import java.util.List;
import java.util.Objects;

/**
 * 채널 무관 알림 메시지. 채널 구현체가 제목·본문 줄을 채널 형식으로 변환.
 */
public record Notification(String title, List<String> lines) {

	public Notification {
		Objects.requireNonNull(title, "title");
		lines = List.copyOf(lines);
	}

	/** 제목과 본문을 줄바꿈으로 연결한 일반 텍스트 */
	public String text() {
		return lines.isEmpty() ? title : title + "\n" + String.join("\n", lines);
	}

}
