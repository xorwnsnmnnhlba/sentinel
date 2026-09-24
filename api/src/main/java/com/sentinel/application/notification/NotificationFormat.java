package com.sentinel.application.notification;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 알림 문구의 시각·기간 표기
 */
public final class NotificationFormat {

	private static final DateTimeFormatter SHORT_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");

	private NotificationFormat() {
	}

	/** 예: 45초, 12분, 2시간 5분 */
	public static String duration(Duration duration) {
		if (duration.toMinutes() < 1) {
			return duration.toSeconds() + "초";
		}
		if (duration.toHours() < 1) {
			return duration.toMinutes() + "분";
		}
		return duration.toHours() + "시간 " + duration.toMinutesPart() + "분";
	}

	/** 예: 09-24 09:10 (알림 본문용 짧은 표기, 연도·시간대 생략) */
	static String shortTime(Instant instant, ZoneId zone) {
		return SHORT_TIME.format(instant.atZone(zone));
	}

}
