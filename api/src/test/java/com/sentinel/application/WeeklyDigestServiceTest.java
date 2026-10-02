package com.sentinel.application;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.sentinel.application.notification.Notification;
import com.sentinel.application.notification.NotificationProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 주간 요약 문구. 집계 값은 각 서비스 테스트에서 검증하므로 집계 결과를 직접 넣어 확인
 */
class WeeklyDigestServiceTest {

	private static final LocalDate FROM = LocalDate.of(2026, 9, 21);

	private static final LocalDate TO = LocalDate.of(2026, 9, 27);

	private static final LocalDate PREVIOUS_FROM = LocalDate.of(2026, 9, 14);

	private static final LocalDate PREVIOUS_TO = LocalDate.of(2026, 9, 20);

	private final SuiteSummaryService suiteSummaries = mock(SuiteSummaryService.class);

	private final UnstableCaseService unstableCases = mock(UnstableCaseService.class);

	private final ResponseTimeService responseTimes = mock(ResponseTimeService.class);

	private final MonitoringGapService monitoringGaps = mock(MonitoringGapService.class);

	@BeforeEach
	void setUp() {
		suites(FROM, TO);
		suites(PREVIOUS_FROM, PREVIOUS_TO);
		when(unstableCases.list(FROM, TO, null)).thenReturn(new UnstableCaseService.UnstableCases(FROM, TO, List.of()));
		when(responseTimes.changes(FROM, TO, WeeklyDigestService.TOP)).thenReturn(
				new ResponseTimeService.Changes(FROM, TO, PREVIOUS_FROM, PREVIOUS_TO, List.of()));
		when(monitoringGaps.list(FROM, TO)).thenReturn(new MonitoringGapService.MonitoringGaps(FROM, TO, List.of()));
	}

	@Test
	void summarizesTheWeekAgainstThePreviousOne() {
		suites(FROM, TO, suite(1, "결제", 600, 15, 1800, 1764), suite(2, "정산", 408, 3, 816, 816),
				suite(3, "회원", 0, 0, 0, 0));
		suites(PREVIOUS_FROM, PREVIOUS_TO, suite(1, "결제", 600, 20, 1800, 1755), suite(2, "정산", 400, 1, 800, 800));

		Notification digest = service("https://sentinel.example.com").build(FROM, TO);

		assertThat(digest.title()).isEqualTo("[Sentinel] 주간 요약 09-21 ~ 09-27");
		// 이번 주 2580/2616 = 98.6%, 지난주 2555/2600 = 98.3%
		assertThat(digest.lines()).containsExactly(
				"실행 1,008회 · 통과율 98.6% (지난주 대비 +0.4%p) · 실패·오류가 있는 실행 18회 (지난주 21회)",
				"통과율 낮은 스위트: 결제 98.0%", "대시보드: https://sentinel.example.com/");
	}

	@Test
	void listsTheTopItemsOfEachSectionAndCountsTheRest() {
		suites(FROM, TO, suite(1, "결제", 10, 5, 100, 50), suite(2, "정산", 10, 2, 100, 90), suite(3, "회원", 10, 1, 100, 99),
				suite(4, "알림", 10, 9, 100, 10), suite(5, "검색", 10, 0, 100, 100));
		when(unstableCases.list(FROM, TO, null)).thenReturn(new UnstableCaseService.UnstableCases(FROM, TO,
				List.of(unstable(1, "결제 승인", "dev"), unstable(2, "환불 조회", "staging"), unstable(3, "c", "dev"),
						unstable(4, "d", "dev"), unstable(5, "e", "dev"))));
		when(responseTimes.changes(FROM, TO, WeeklyDigestService.TOP))
			.thenReturn(new ResponseTimeService.Changes(FROM, TO, PREVIOUS_FROM, PREVIOUS_TO,
					List.of(change("결제 승인", 320, 610, 0.91), change("환불 조회", 100, 120, 0.2),
							change("정산 조회", 200, 210, 0.05), change("회원 조회", 100, 100, 0.0),
							change("검색", 100, 90, -0.1))));
		when(monitoringGaps.list(FROM, TO)).thenReturn(new MonitoringGapService.MonitoringGaps(FROM, TO,
				List.of(gap("2026-09-22T00:10:00Z", "2026-09-22T02:40:00Z"),
						gap("2026-09-24T14:00:00Z", "2026-09-24T15:30:00Z"))));

		Notification digest = service(null).build(FROM, TO);

		// 지난주 실행이 없으면 비교하지 않음. 대시보드 주소 미지정 시 링크 없음
		assertThat(digest.lines()).containsExactly("실행 50회 · 통과율 69.8% · 실패·오류가 있는 실행 17회",
				"통과율 낮은 스위트: 알림 10.0%, 결제 50.0%, 정산 90.0% 외 1건",
				"불안정 케이스 5건: 결제 승인 (dev), 환불 조회 (staging), c (dev) 외 2건",
				// 빨라졌거나 같은 케이스는 제외. 딱 3건이면 "외" 없음
				"응답시간 나빠진 케이스 (p95): 결제 승인 (dev) 320ms → 610ms, 환불 조회 (dev) 100ms → 120ms, 정산 조회 (dev) 200ms → 210ms",
				"모니터링 공백 2건 · 4시간 0분");
	}

	@Test
	void asksToCheckTheSchedulerWhenNothingRan() {
		suites(FROM, TO, suite(1, "결제", 0, 0, 0, 0));
		when(monitoringGaps.list(FROM, TO)).thenReturn(new MonitoringGapService.MonitoringGaps(FROM, TO,
				List.of(gap("2026-09-21T00:00:00Z", "2026-09-27T00:00:00Z"))));

		Notification digest = service(null).build(FROM, TO);

		assertThat(digest.lines()).containsExactly(
				"기간 안 실행 없음 - 스케줄이 꺼져 있거나 Sentinel이 멈춰 있었는지 확인해 주세요", "모니터링 공백 1건 · 144시간 0분");
	}

	@Test
	void formatsPassRateChangeInPercentPoints() {
		assertThat(WeeklyDigestService.percent(0.98234)).isEqualTo("98.2%");
		assertThat(WeeklyDigestService.percent(1)).isEqualTo("100.0%");
		assertThat(WeeklyDigestService.pointChange(0.004)).isEqualTo("지난주 대비 +0.4%p");
		assertThat(WeeklyDigestService.pointChange(-0.012)).isEqualTo("지난주 대비 -1.2%p");
		assertThat(WeeklyDigestService.pointChange(0.0004)).isEqualTo("지난주와 같음");
		assertThat(WeeklyDigestService.pointChange(-0.0004)).isEqualTo("지난주와 같음");
	}

	private WeeklyDigestService service(String webBaseUrl) {
		return new WeeklyDigestService(suiteSummaries, unstableCases, responseTimes, monitoringGaps,
				new NotificationProperties(null, webBaseUrl, Duration.ofSeconds(3), Duration.ofSeconds(5)));
	}

	private void suites(LocalDate from, LocalDate to, SuiteSummaryService.SuiteSummary... suites) {
		when(suiteSummaries.summaries(from, to)).thenReturn(new SuiteSummaryService.Summaries(from, to, List.of(suites)));
	}

	private static SuiteSummaryService.SuiteSummary suite(long id, String name, long runs, long failingRuns, int results,
			int passed) {
		return new SuiteSummaryService.SuiteSummary(new IdName(id, name), new IdName(1, "프로젝트"), runs, failingRuns,
				new TestRunDetail.Summary(results, passed, results - passed, 0),
				results == 0 ? null : (double) passed / results, null);
	}

	private static UnstableCaseService.UnstableCase unstable(long id, String name, String environment) {
		return new UnstableCaseService.UnstableCase(new IdName(id, name), new IdName(1, "결제"),
				new IdName(environment.length(), environment), 100, 5, 2, 0);
	}

	private static ResponseTimeService.Change change(String name, long previousP95, long currentP95, double change) {
		return new ResponseTimeService.Change(new IdName(name.length(), name), new IdName(1, "결제"), new IdName(7, "dev"),
				previousP95, currentP95, change, 10, 10);
	}

	private static MonitoringGapService.Gap gap(String startedAt, String endedAt) {
		return new MonitoringGapService.Gap(1, Instant.parse(startedAt), Instant.parse(endedAt), 1);
	}

}
