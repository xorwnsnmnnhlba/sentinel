package com.sentinel.application.notification;

import java.util.ArrayList;
import java.util.List;

import com.sentinel.runner.HttpMethod;

/**
 * 실행 1회의 실패 요약. 알림 문구 생성용.
 *
 * @param failures 판정 실패(FAILED)·오류(ERROR) 케이스
 * @param runUrl 결과 화면 주소. 미설정 시 null
 */
record RunReport(String suiteName, String environmentName, int total, List<FailedCase> failures, String runUrl) {

	static final int MAX_LINES = 20;

	boolean hasFailures() {
		return !failures.isEmpty();
	}

	/**
	 * <pre>
	 * [Sentinel] dev 결제 - 12건 중 2건 실패
	 * POST   /billing/v1/charge → status in [2xx]: was 500
	 * GET    /billing/v1/history → response time &lt;= 2000ms: was 3200ms
	 * 결과: http://sentinel.example.com/runs/12
	 * </pre>
	 */
	Notification toNotification() {
		String title = "[Sentinel] %s %s - %d건 중 %d건 실패".formatted(environmentName, suiteName, total,
				failures.size());
		List<String> lines = new ArrayList<>();
		failures.stream()
			.limit(MAX_LINES)
			.forEach(failure -> lines.add("%-6s %s → %s".formatted(failure.method(), failure.path(), failure.reason())));
		if (failures.size() > MAX_LINES) {
			lines.add("… 외 " + (failures.size() - MAX_LINES) + "건");
		}
		if (runUrl != null) {
			lines.add("결과: " + runUrl);
		}
		return new Notification(title, lines);
	}

	record FailedCase(HttpMethod method, String path, String reason) {
	}

}
