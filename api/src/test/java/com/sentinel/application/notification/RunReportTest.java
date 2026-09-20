package com.sentinel.application.notification;

import java.util.List;
import java.util.stream.IntStream;

import com.sentinel.runner.HttpMethod;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RunReportTest {

	@Test
	void formatsTitleAndOneLinePerFailedCase() {
		RunReport report = new RunReport("결제", "dev", 12,
				List.of(new RunReport.FailedCase(HttpMethod.POST, "/billing/v1/charge", "status in [2xx]: was 500"),
						new RunReport.FailedCase(HttpMethod.GET, "/billing/v1/history",
								"response time <= 2000ms: was 3200ms")),
				null);

		Notification notification = report.toNotification();

		assertThat(notification.title()).isEqualTo("[Sentinel] dev 결제 - 12건 중 2건 실패");
		assertThat(notification.lines()).containsExactly("POST   /billing/v1/charge → status in [2xx]: was 500",
				"GET    /billing/v1/history → response time <= 2000ms: was 3200ms");
		assertThat(notification.text()).startsWith("[Sentinel] dev 결제 - 12건 중 2건 실패\nPOST   /billing");
	}

	@Test
	void limitsLinesAndSummarizesTheRest() {
		List<RunReport.FailedCase> failures = IntStream.range(0, 25)
			.mapToObj(i -> new RunReport.FailedCase(HttpMethod.GET, "/c/" + i, "TIMEOUT"))
			.toList();

		Notification notification = new RunReport("s", "dev", 30, failures, null).toNotification();

		assertThat(notification.title()).endsWith("30건 중 25건 실패");
		assertThat(notification.lines()).hasSize(RunReport.MAX_LINES + 1).last().isEqualTo("… 외 5건");
	}

	@Test
	void reportsNoFailuresWhenAllPassed() {
		assertThat(new RunReport("s", "dev", 3, List.of(), null).hasFailures()).isFalse();
	}

	@Test
	void addsTheRunLinkWhenTheWebAddressIsConfigured() {
		RunReport report = new RunReport("결제", "dev", 3,
				List.of(new RunReport.FailedCase(HttpMethod.GET, "/a", "TIMEOUT")),
				"http://sentinel.example.com/runs/12");

		assertThat(report.toNotification().lines()).last().isEqualTo("결과: http://sentinel.example.com/runs/12");
	}

}
