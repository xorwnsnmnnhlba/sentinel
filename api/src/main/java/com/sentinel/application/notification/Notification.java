package com.sentinel.application.notification;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 채널 무관 알림 메시지. 채널 형식({@link WebhookPayload})이 제목·본문 줄·성격·링크를 채널에 맞게 변환.
 *
 * @param tone 알림 성격. 채널 서식의 색 (Slack 색 막대, Teams 제목 색)
 * @param links 결과·대시보드 등 바로가기. 일반 텍스트에서는 본문 끝에 "이름: 주소" 줄로
 */
public record Notification(String title, List<String> lines, Tone tone, List<Link> links) {

	public Notification {
		Objects.requireNonNull(title, "title");
		Objects.requireNonNull(tone, "tone");
		lines = List.copyOf(lines);
		links = List.copyOf(links);
	}

	/** 성격 구분이 없는 알림 (연결 확인 등) */
	public Notification(String title, List<String> lines) {
		this(title, lines, Tone.INFO, List.of());
	}

	/** 링크 주소가 null(웹 주소 미설정)이면 링크 없이 */
	public static List<Link> linkIfPresent(String label, String url) {
		return url == null ? List.of() : List.of(new Link(label, url));
	}

	/** 제목, 본문, 링크 줄("결과: 주소")을 줄바꿈으로 연결한 일반 텍스트 */
	public String text() {
		List<String> all = new ArrayList<>();
		all.add(title);
		all.addAll(lines);
		links.forEach(link -> all.add(link.label() + ": " + link.url()));
		return String.join("\n", all);
	}

	/** INFO: 요약·확인, SUCCESS: 복구·회복, WARNING: 악화·공백, FAILURE: 실패·장애 */
	public enum Tone {
		INFO, SUCCESS, WARNING, FAILURE
	}

	/** @param label 예: 결과, 대시보드 */
	public record Link(String label, String url) {

		public Link {
			Objects.requireNonNull(label, "label");
			Objects.requireNonNull(url, "url");
		}

	}

}
