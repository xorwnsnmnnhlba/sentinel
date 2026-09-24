package com.sentinel.application.notification;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RecoveryReportTest {

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	/** 2026-09-24 09:10 KST */
	private static final Instant SINCE = Instant.parse("2026-09-24T00:10:00Z");

	@Test
	void summarizesWhatRecoveredAndHowLongItFailed() {
		RecoveryReport report = new RecoveryReport("결제", "dev", 12, List.of("결제 승인", "결제 내역"), 3, false, SINCE,
				SINCE.plus(Duration.ofHours(2)), "http://sentinel.example.com/runs/15", SEOUL);

		Notification notification = report.toNotification();

		assertThat(notification.title()).isEqualTo("[Sentinel] dev 결제 - 복구, 12건 모두 통과");
		assertThat(notification.lines()).containsExactly("다시 통과: 결제 승인, 결제 내역",
				"실패 지속: 스케줄 실행 3회, 09-24 09:10부터 2시간 0분", "결과: http://sentinel.example.com/runs/15");
	}

	@Test
	void shortensLongListsAndMarksCappedStreaks() {
		List<String> names = IntStream.range(0, 12).mapToObj(i -> "case " + i).toList();

		Notification notification = new RecoveryReport("s", "dev", 12, names, RunResultNotifier.LOOKBACK, true, SINCE,
				SINCE.plus(Duration.ofMinutes(40)), null, SEOUL)
			.toNotification();

		assertThat(notification.lines()).containsExactly(
				"다시 통과: case 0, case 1, case 2, case 3, case 4, case 5, case 6, case 7, case 8, case 9 외 2건",
				"실패 지속: 스케줄 실행 20회 이상, 09-24 09:10부터 40분");
	}

}
