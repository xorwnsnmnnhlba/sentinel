package com.sentinel.application;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import com.sentinel.application.notification.Notification;
import com.sentinel.application.notification.NotificationFormat;
import com.sentinel.application.notification.NotificationProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주간 요약 알림 문구. 대시보드와 같은 집계(스위트 요약·불안정 케이스·응답시간 변화·모니터링 공백)를 한 알림으로.
 * 해당 항목이 없는 줄은 생략. 기간에 실행이 없으면 스케줄이 멈췄는지 확인하도록 안내
 */
@Service
@RequiredArgsConstructor
public class WeeklyDigestService {

	/** 줄마다 보여 줄 항목 수. 나머지는 "외 N건" */
	static final int TOP = 3;

	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MM-dd");

	private final SuiteSummaryService suiteSummaries;

	private final UnstableCaseService unstableCases;

	private final ResponseTimeService responseTimes;

	private final MonitoringGapService monitoringGaps;

	private final NotificationProperties notificationProperties;

	/**
	 * @param from 기간 시작일(포함)
	 * @param to 기간 종료일(포함). 비교 기간은 바로 앞의 같은 길이
	 */
	@Transactional(readOnly = true)
	public Notification build(LocalDate from, LocalDate to) {
		long days = to.toEpochDay() - from.toEpochDay() + 1;
		Totals current = Totals.of(suiteSummaries.summaries(from, to).suites());
		Totals previous = Totals.of(suiteSummaries.summaries(from.minusDays(days), from.minusDays(1)).suites());

		List<String> lines = new ArrayList<>();
		if (current.runs() == 0) {
			lines.add("기간 안 실행 없음 - 스케줄이 꺼져 있거나 Sentinel이 멈춰 있었는지 확인해 주세요");
		}
		else {
			lines.add(runsLine(current, previous.runs() > 0 ? previous : null));
			List<SuiteSummaryService.SuiteSummary> lowest = current.suites()
				.stream()
				.filter(suite -> suite.passRate() != null && suite.passRate() < 1)
				.sorted(Comparator.comparingDouble(SuiteSummaryService.SuiteSummary::passRate))
				.toList();
			if (!lowest.isEmpty()) {
				lines.add("통과율 낮은 스위트: "
						+ top(lowest, suite -> suite.suite().name() + " " + percent(suite.passRate())));
			}
		}

		List<UnstableCaseService.UnstableCase> unstable = unstableCases.list(from, to, null).cases();
		if (!unstable.isEmpty()) {
			lines.add("불안정 케이스 " + unstable.size() + "건: "
					+ top(unstable, item -> item.testCase().name() + " (" + item.environment().name() + ")"));
		}

		List<ResponseTimeService.Change> slower = responseTimes.changes(from, to, TOP)
			.changes()
			.stream()
			.filter(change -> change.change() > 0)
			.toList();
		if (!slower.isEmpty()) {
			lines.add("응답시간 나빠진 케이스 (p95): " + top(slower,
					change -> change.testCase().name() + " (" + change.environment().name() + ") "
							+ change.previousP95() + "ms → " + change.currentP95() + "ms"));
		}

		List<MonitoringGapService.Gap> gaps = monitoringGaps.list(from, to).gaps();
		if (!gaps.isEmpty()) {
			Duration total = gaps.stream()
				.map(gap -> Duration.between(gap.startedAt(), gap.endedAt()))
				.reduce(Duration.ZERO, Duration::plus);
			lines.add("모니터링 공백 " + gaps.size() + "건 · " + NotificationFormat.duration(total));
		}

		String dashboard = notificationProperties.dashboardUrl();
		if (dashboard != null) {
			lines.add("대시보드: " + dashboard);
		}
		return new Notification("[Sentinel] 주간 요약 " + DAY.format(from) + " ~ " + DAY.format(to), lines);
	}

	/** 예: 실행 1,008회 · 통과율 98.2% (지난주 대비 +0.4%p) · 실패·오류가 있는 실행 18회 (지난주 21회) */
	private static String runsLine(Totals current, Totals previous) {
		StringBuilder line = new StringBuilder("실행 ").append(count(current.runs())).append("회");
		if (current.passRate() != null) {
			line.append(" · 통과율 ").append(percent(current.passRate()));
			if (previous != null && previous.passRate() != null) {
				line.append(" (").append(pointChange(current.passRate() - previous.passRate())).append(")");
			}
		}
		line.append(" · 실패·오류가 있는 실행 ").append(count(current.failingRuns())).append("회");
		if (previous != null) {
			line.append(" (지난주 ").append(count(previous.failingRuns())).append("회)");
		}
		return line.toString();
	}

	private static <T> String top(List<T> items, Function<T, String> label) {
		String shown = String.join(", ", items.stream().limit(TOP).map(label).toList());
		return items.size() > TOP ? shown + " 외 " + (items.size() - TOP) + "건" : shown;
	}

	private static String count(long value) {
		return String.format(Locale.ROOT, "%,d", value);
	}

	/** 0.9823 → 98.2% */
	static String percent(double rate) {
		return String.format(Locale.ROOT, "%.1f%%", rate * 100);
	}

	/** 통과율 차이(0~1)를 %p로. 반올림해 0.0%p면 같음. 예: 지난주 대비 +0.4%p, 지난주 대비 -1.2%p */
	static String pointChange(double difference) {
		double points = Math.round(difference * 1000) / 10.0;
		return points == 0 ? "지난주와 같음" : String.format(Locale.ROOT, "지난주 대비 %+.1f%%p", points);
	}

	/**
	 * 스위트 요약 합계 (대시보드 요약 타일과 같은 기준)
	 * @param passRate 기간 안 결과 중 통과 비율. 결과가 없으면 null
	 */
	private record Totals(List<SuiteSummaryService.SuiteSummary> suites, long runs, long failingRuns, Double passRate) {

		static Totals of(List<SuiteSummaryService.SuiteSummary> suites) {
			long runs = suites.stream().mapToLong(SuiteSummaryService.SuiteSummary::runs).sum();
			long failingRuns = suites.stream().mapToLong(SuiteSummaryService.SuiteSummary::failingRuns).sum();
			long total = suites.stream().mapToLong(suite -> suite.results().total()).sum();
			long passed = suites.stream().mapToLong(suite -> suite.results().passed()).sum();
			return new Totals(suites, runs, failingRuns, total == 0 ? null : (double) passed / total);
		}

	}

}
