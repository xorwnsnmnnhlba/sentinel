package com.sentinel.application.notification;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 실패하던 스위트가 모두 통과한 실행의 요약. 알림 문구 생성용.
 *
 * @param recovered 직전 실행에서 실패했다가 이번에 통과한 케이스 이름
 * @param failingRuns 실패가 이어진 이전 스케줄 실행 수
 * @param capped 확인한 이전 실행이 모두 실패라 실제로는 더 길 수 있음
 * @param failingSince 실패가 시작된 실행의 시작 시각
 * @param recoveredAt 이번 실행의 시작 시각
 */
record RecoveryReport(String suiteName, String environmentName, int total, List<String> recovered, int failingRuns,
		boolean capped, Instant failingSince, Instant recoveredAt, String runUrl, ZoneId zone) {

	static final int MAX_NAMES = 10;

	/**
	 * <pre>
	 * [Sentinel] dev 결제 - 복구, 12건 모두 통과
	 * 다시 통과: 결제 승인, 결제 내역
	 * 실패 지속: 스케줄 실행 3회, 09-24 09:10부터 2시간 0분
	 * 결과: http://sentinel.example.com/runs/15
	 * </pre>
	 */
	Notification toNotification() {
		String title = "[Sentinel] %s %s - 복구, %d건 모두 통과".formatted(environmentName, suiteName, total);
		List<String> lines = new ArrayList<>();
		if (!recovered.isEmpty()) {
			String names = String.join(", ", recovered.stream().limit(MAX_NAMES).toList());
			lines.add("다시 통과: " + names
					+ (recovered.size() > MAX_NAMES ? " 외 " + (recovered.size() - MAX_NAMES) + "건" : ""));
		}
		lines.add("실패 지속: 스케줄 실행 %d회%s, %s부터 %s".formatted(failingRuns, capped ? " 이상" : "",
				NotificationFormat.shortTime(failingSince, zone),
				NotificationFormat.duration(Duration.between(failingSince, recoveredAt))));
		return new Notification(title, lines, Notification.Tone.SUCCESS, Notification.linkIfPresent("결과", runUrl));
	}

}
