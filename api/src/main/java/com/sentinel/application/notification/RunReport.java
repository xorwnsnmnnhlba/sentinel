package com.sentinel.application.notification;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import com.sentinel.runner.HttpMethod;

/**
 * 실행 1회의 실패 요약. 알림 문구 생성용.
 *
 * @param failures 판정 실패(FAILED)·오류(ERROR) 케이스. 새로 실패한 케이스가 앞
 * @param runUrl 결과 화면 주소. 미설정 시 null
 * @param zone 시각 표기 시간대
 */
record RunReport(String suiteName, String environmentName, int total, List<FailedCase> failures, String runUrl,
		ZoneId zone) {

	static final int MAX_LINES = 20;

	boolean hasFailures() {
		return !failures.isEmpty();
	}

	/**
	 * <pre>
	 * [Sentinel] dev 결제 - 12건 중 2건 실패 (새로 실패 1건)
	 * [새로 실패] 결제 승인 · POST /billing/v1/charge → status in [2xx]: was 500
	 * [3회째 · 09-24 09:10부터] 결제 내역 · GET /billing/v1/history → response time &lt;= 2000ms: was 3200ms
	 * 결과: http://sentinel.example.com/runs/12
	 * </pre>
	 * 모두 이전부터 실패하던 케이스면 제목 끝이 "(계속 실패 중)"
	 */
	Notification toNotification() {
		long newFailures = failures.stream().filter(failure -> failure.streak() == 1).count();
		String state = newFailures > 0 ? "새로 실패 " + newFailures + "건" : "계속 실패 중";
		String title = "[Sentinel] %s %s - %d건 중 %d건 실패 (%s)".formatted(environmentName, suiteName, total,
				failures.size(), state);
		List<String> lines = new ArrayList<>();
		failures.stream()
			.limit(MAX_LINES)
			.forEach(failure -> lines.add("[%s] %s · %s %s → %s".formatted(tag(failure), failure.name(),
					failure.method(), failure.path(), failure.reason())));
		if (failures.size() > MAX_LINES) {
			lines.add("… 외 " + (failures.size() - MAX_LINES) + "건");
		}
		if (runUrl != null) {
			lines.add("결과: " + runUrl);
		}
		return new Notification(title, lines);
	}

	private String tag(FailedCase failure) {
		if (failure.streak() == 1) {
			return "새로 실패";
		}
		String count = failure.capped() ? failure.streak() + "회 이상" : failure.streak() + "회째";
		return count + " · " + NotificationFormat.shortTime(failure.since(), zone) + "부터";
	}

	/**
	 * @param streak 이번 실행을 포함한 연속 실패 횟수 (1이면 새로 실패)
	 * @param capped 확인한 이전 실행이 모두 실패라 실제로는 더 길 수 있음
	 * @param since 연속 실패가 시작된 실행의 시작 시각
	 */
	record FailedCase(String name, HttpMethod method, String path, String reason, int streak, boolean capped,
			Instant since) {
	}

}
