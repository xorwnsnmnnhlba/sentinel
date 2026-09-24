package com.sentinel.application.notification;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.IntStream;

import com.sentinel.runner.HttpMethod;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RunReportTest {

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	/** 2026-09-24 09:10 KST */
	private static final Instant SINCE = Instant.parse("2026-09-24T00:10:00Z");

	private static RunReport.FailedCase newFailure(String name, HttpMethod method, String path, String reason) {
		return new RunReport.FailedCase(name, method, path, reason, 1, false, SINCE);
	}

	@Test
	void marksNewAndOngoingFailures() {
		RunReport report = new RunReport("결제", "dev", 12,
				List.of(newFailure("결제 승인", HttpMethod.POST, "/billing/v1/charge", "status in [2xx]: was 500"),
						new RunReport.FailedCase("결제 내역", HttpMethod.GET, "/billing/v1/history",
								"response time <= 2000ms: was 3200ms", 3, false, SINCE)),
				null, SEOUL);

		Notification notification = report.toNotification();

		assertThat(notification.title()).isEqualTo("[Sentinel] dev 결제 - 12건 중 2건 실패 (새로 실패 1건)");
		assertThat(notification.lines()).containsExactly(
				"[새로 실패] 결제 승인 · POST /billing/v1/charge → status in [2xx]: was 500",
				"[3회째 · 09-24 09:10부터] 결제 내역 · GET /billing/v1/history → response time <= 2000ms: was 3200ms");
	}

	@Test
	void saysTheFailuresAreOngoingWhenNoneIsNew() {
		RunReport report = new RunReport("결제", "dev", 12,
				List.of(new RunReport.FailedCase("결제 승인", HttpMethod.POST, "/charge", "TIMEOUT",
						RunResultNotifier.LOOKBACK + 1, true, SINCE)),
				null, SEOUL);

		Notification notification = report.toNotification();

		assertThat(notification.title()).endsWith("12건 중 1건 실패 (계속 실패 중)");
		// 확인한 범위를 넘어 이어졌을 수 있으면 "이상"
		assertThat(notification.lines()).containsExactly("[21회 이상 · 09-24 09:10부터] 결제 승인 · POST /charge → TIMEOUT");
	}

	@Test
	void limitsLinesAndSummarizesTheRest() {
		List<RunReport.FailedCase> failures = IntStream.range(0, 25)
			.mapToObj(i -> newFailure("case " + i, HttpMethod.GET, "/c/" + i, "TIMEOUT"))
			.toList();

		Notification notification = new RunReport("s", "dev", 30, failures, null, SEOUL).toNotification();

		assertThat(notification.title()).endsWith("30건 중 25건 실패 (새로 실패 25건)");
		assertThat(notification.lines()).hasSize(RunReport.MAX_LINES + 1).last().isEqualTo("… 외 5건");
	}

	@Test
	void reportsNoFailuresWhenAllPassed() {
		assertThat(new RunReport("s", "dev", 3, List.of(), null, SEOUL).hasFailures()).isFalse();
	}

	@Test
	void addsTheRunLinkWhenTheWebAddressIsConfigured() {
		RunReport report = new RunReport("결제", "dev", 3, List.of(newFailure("a", HttpMethod.GET, "/a", "TIMEOUT")),
				"http://sentinel.example.com/runs/12", SEOUL);

		assertThat(report.toNotification().lines()).last().isEqualTo("결과: http://sentinel.example.com/runs/12");
	}

}
