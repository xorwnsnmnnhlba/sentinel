package com.sentinel.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.sentinel.application.notification.Notification;
import com.sentinel.application.notification.NotificationChannels;
import com.sentinel.application.notification.NotificationProperties;
import com.sentinel.application.notification.Notifier;
import com.sentinel.domain.Environment;
import com.sentinel.domain.Project;
import com.sentinel.domain.SlowdownAlert;
import com.sentinel.domain.TestCase;
import com.sentinel.infra.persistence.EnvironmentRepository;
import com.sentinel.infra.persistence.SlowdownAlertRepository;
import com.sentinel.infra.persistence.TestCaseRepository;
import com.sentinel.infra.persistence.TestResultRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

/**
 * 응답시간 악화 알림. 케이스·환경별 최근 기간(기본 24시간) p95를 그 앞 비교 기간(기본 7일)과 비교하여, 기준 이상 나빠지면 1회 알리고
 * 기준 아래로 돌아오면 회복을 1회 알림. 느린 동안은 다시 알리지 않음 ({@link SlowdownAlert})
 * <p>
 * 프로젝트별로 묶어 프로젝트 채널로 발송. 두 기간 모두 응답이 {@link SlowdownProperties#minSamples()}건 이상인 경우만 판단하며,
 * 최근 기간 표본이 모자라면(스케줄 중지 등) 열린 알림은 그대로 둠. 삭제된 케이스·환경의 열린 알림은 알리지 않고 닫음
 */
@Component
@RequiredArgsConstructor
class SlowdownDetector {

	private static final Logger log = LoggerFactory.getLogger(SlowdownDetector.class);

	private final SlowdownProperties properties;

	private final TestResultRepository results;

	private final SlowdownAlertRepository alerts;

	private final TestCaseRepository testCases;

	private final EnvironmentRepository environments;

	private final NotificationChannels channels;

	private final NotificationProperties notificationProperties;

	private final Transactions transactions;

	private final Clock clock;

	/** @return 이번 확인에서 새로 느려진 수·회복한 수 */
	Outcome check() {
		Instant now = clock.instant();
		List<Message> messages;
		Outcome outcome;
		try {
			Evaluation evaluation = transactions.write(status -> evaluate(now));
			messages = evaluation.messages();
			outcome = evaluation.outcome();
		}
		catch (DataAccessException | TransactionException ex) {
			log.warn("Skipping slowdown check: database unavailable ({})", ex.getMostSpecificCause().getMessage());
			return new Outcome(0, 0);
		}
		// 발송은 트랜잭션 밖에서 (채널 지연이 DB 연결을 붙잡지 않도록)
		messages.forEach(message -> send(message.channel(), message.notification()));
		if (outcome.slowed() > 0 || outcome.recovered() > 0) {
			log.info("Slowdown check: {} slowed, {} recovered", outcome.slowed(), outcome.recovered());
		}
		return outcome;
	}

	private Evaluation evaluate(Instant now) {
		Instant middle = now.minus(properties.recentWindow());
		Map<Key, Window[]> windows = new HashMap<>();
		for (Object[] row : results.p95ByWindow(middle.minus(properties.baselineWindow()), middle, now)) {
			Key key = new Key(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
			long samples = ((Number) row[3]).longValue();
			if (samples < properties.minSamples() || row[4] == null) {
				continue;
			}
			windows.computeIfAbsent(key, k -> new Window[2])[((Number) row[2]).intValue()] = new Window(samples,
					Math.round(((Number) row[4]).doubleValue()));
		}
		Map<Key, SlowdownAlert> open = new HashMap<>();
		alerts.findByResolvedAtIsNull().forEach(alert -> open.put(Key.of(alert), alert));

		List<Long> caseIds = new ArrayList<>();
		List<Long> environmentIds = new ArrayList<>();
		windows.keySet().forEach(key -> add(key, caseIds, environmentIds));
		open.keySet().forEach(key -> add(key, caseIds, environmentIds));
		Map<Long, TestCase> cases = new HashMap<>();
		Map<Long, Environment> environmentsById = new HashMap<>();
		if (!caseIds.isEmpty()) {
			testCases.findActiveWithSuiteByIdIn(caseIds).forEach(testCase -> cases.put(testCase.getId(), testCase));
			environments.findAllById(environmentIds)
				.stream()
				.filter(environment -> !environment.isArchived())
				.forEach(environment -> environmentsById.put(environment.getId(), environment));
		}

		List<Line> slowed = new ArrayList<>();
		List<Line> recovered = new ArrayList<>();
		for (Map.Entry<Key, SlowdownAlert> entry : open.entrySet()) {
			Key key = entry.getKey();
			SlowdownAlert alert = entry.getValue();
			if (!cases.containsKey(key.caseId()) || !environmentsById.containsKey(key.environmentId())) {
				// 삭제된 케이스·환경: 판단할 수 없으므로 알리지 않고 닫음
				alert.resolve(alert.getDetectedP95(), now);
				continue;
			}
			Window recent = recentOf(windows.get(key));
			if (recent != null && properties.isRecovered(alert.getBaselineP95(), recent.p95())) {
				alert.resolve(recent.p95(), now);
				recovered.add(new Line(cases.get(key.caseId()), environmentsById.get(key.environmentId()),
						alert.getBaselineP95(), recent.p95(), alert.getDetectedP95(), recent.samples()));
			}
		}
		for (Map.Entry<Key, Window[]> entry : windows.entrySet()) {
			Key key = entry.getKey();
			Window baseline = entry.getValue()[0];
			Window recent = entry.getValue()[1];
			if (open.containsKey(key) || baseline == null || recent == null || baseline.p95() <= 0
					|| !cases.containsKey(key.caseId()) || !environmentsById.containsKey(key.environmentId())
					|| !properties.isSlow(baseline.p95(), recent.p95())) {
				continue;
			}
			alerts.save(new SlowdownAlert(key.caseId(), key.environmentId(), baseline.p95(), recent.p95(), now));
			slowed.add(new Line(cases.get(key.caseId()), environmentsById.get(key.environmentId()), baseline.p95(),
					recent.p95(), recent.p95(), recent.samples()));
		}

		List<Message> messages = new ArrayList<>();
		byProject(slowed)
			.forEach(lines -> messages.add(message(lines, "응답시간 악화", Notification.Tone.WARNING, Line::slowedText)));
		byProject(recovered)
			.forEach(lines -> messages.add(message(lines, "응답시간 회복", Notification.Tone.SUCCESS, Line::recoveredText)));
		return new Evaluation(messages, new Outcome(slowed.size(), recovered.size()));
	}

	/** 프로젝트 하나의 알림. 프로젝트 Webhook이 있으면 그 채널 (암호문 조회는 이 트랜잭션에서) */
	private Message message(List<Line> lines, String kind, Notification.Tone tone, Function<Line, String> text) {
		Project project = lines.getFirst().testCase().getSuite().getProject();
		return new Message(channels.forProject(project), notification(project, kind, tone, lines, text));
	}

	private Notification notification(Project project, String kind, Notification.Tone tone, List<Line> lines,
			Function<Line, String> text) {
		List<String> body = lines.stream().sorted(Comparator.comparingDouble(Line::increase).reversed()).map(text).toList();
		return new Notification("[Sentinel] " + project.getName() + " - " + kind + " " + lines.size() + "건", body, tone,
				Notification.linkIfPresent("대시보드", notificationProperties.dashboardUrl()));
	}

	/** 프로젝트별로 묶음. 프로젝트 이름 순 (같은 확인 안에서 알림 순서를 일정하게) */
	private static List<List<Line>> byProject(List<Line> lines) {
		Map<Long, List<Line>> grouped = new LinkedHashMap<>();
		lines.stream()
			.sorted(Comparator.comparing((Line line) -> line.testCase().getSuite().getProject().getName()))
			.forEach(line -> grouped
				.computeIfAbsent(line.testCase().getSuite().getProject().getId(), id -> new ArrayList<>())
				.add(line));
		return List.copyOf(grouped.values());
	}

	private static Window recentOf(Window[] pair) {
		return pair == null ? null : pair[1];
	}

	private static void add(Key key, List<Long> caseIds, List<Long> environmentIds) {
		caseIds.add(key.caseId());
		environmentIds.add(key.environmentId());
	}

	private void send(Notifier channel, Notification notification) {
		try {
			channel.send(notification);
		}
		catch (RuntimeException ex) {
			log.error("Failed to send slowdown notification: {}", notification.title(), ex);
		}
	}

	/** @param slowed 새로 느려진 케이스·환경 수 */
	record Outcome(int slowed, int recovered) {
	}

	private record Evaluation(List<Message> messages, Outcome outcome) {
	}

	private record Message(Notifier channel, Notification notification) {
	}

	private record Key(long caseId, long environmentId) {

		static Key of(SlowdownAlert alert) {
			return new Key(alert.getTestCaseId(), alert.getEnvironmentId());
		}

	}

	/** 기간 하나의 응답 수·p95(ms) */
	private record Window(long samples, long p95) {
	}

	/**
	 * 알림 한 줄
	 * @param detectedP95 악화 감지 당시 최근 p95 (회복 알림에 표시)
	 */
	private record Line(TestCase testCase, Environment environment, long baselineP95, long recentP95, long detectedP95,
			long samples) {

		double increase() {
			return (double) (recentP95 - baselineP95) / baselineP95;
		}

		/** 예: 결제 · 결제 승인 (dev) p95 320ms → 610ms (+91%, 최근 응답 144건) */
		String slowedText() {
			return "%s · %s (%s) p95 %dms → %dms (+%d%%, 최근 응답 %d건)".formatted(testCase.getSuite().getName(),
					testCase.getName(), environment.getName(), baselineP95, recentP95, Math.round(increase() * 100),
					samples);
		}

		/** 예: 결제 · 결제 승인 (dev) p95 340ms로 회복 (악화 당시 610ms, 기준 320ms) */
		String recoveredText() {
			return "%s · %s (%s) p95 %dms로 회복 (악화 당시 %dms, 기준 %dms)".formatted(testCase.getSuite().getName(),
					testCase.getName(), environment.getName(), recentP95, detectedP95, baselineP95);
		}

	}

}
