package com.sentinel.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.SimpleTriggerContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AlignedPollTriggerTest {

	private static final Duration MINUTE = Duration.ofSeconds(60);

	@Test
	void runsJustAfterTheNextBoundary() {
		assertThat(AlignedPollTrigger.nextAfter(Instant.parse("2026-10-26T00:00:30Z"), MINUTE))
			.isEqualTo(Instant.parse("2026-10-26T00:01:01Z"));
		// 경계는 지났지만 여유 1초 전이면 이번 경계
		assertThat(AlignedPollTrigger.nextAfter(Instant.parse("2026-10-26T00:00:00.500Z"), MINUTE))
			.isEqualTo(Instant.parse("2026-10-26T00:00:01Z"));
		// 정확히 실행 시각이면 다음 경계 (같은 시각에 두 번 실행하지 않음)
		assertThat(AlignedPollTrigger.nextAfter(Instant.parse("2026-10-26T00:00:01Z"), MINUTE))
			.isEqualTo(Instant.parse("2026-10-26T00:01:01Z"));
		assertThat(AlignedPollTrigger.nextAfter(Instant.parse("2026-10-26T00:00:00.999Z"), MINUTE))
			.isEqualTo(Instant.parse("2026-10-26T00:00:01Z"));
		// 분이 아닌 주기도 주기의 배수 시각에
		assertThat(AlignedPollTrigger.nextAfter(Instant.parse("2026-10-26T00:00:10Z"), Duration.ofSeconds(45)))
			.isEqualTo(Instant.parse("2026-10-26T00:00:46Z"));
	}

	@Test
	void doesNotDriftWhenEachPollTakesTime() {
		// 장시간 실행 점검에서 본 상황: 폴링마다 스위트 실행에 몇 초. 끝난 뒤 60초를 기다리면 약 22분마다 한 분이 빠졌음
		AlignedPollTrigger trigger = new AlignedPollTrigger(MINUTE);
		SimpleTriggerContext context = new SimpleTriggerContext(
				Clock.fixed(Instant.parse("2026-10-26T00:00:30Z"), ZoneOffset.UTC));
		List<Instant> runs = new ArrayList<>();
		for (int i = 0; i < 180; i++) {
			Instant scheduled = trigger.nextExecution(context);
			runs.add(scheduled);
			Instant completed = scheduled.plusMillis(2_500);
			context.update(scheduled, scheduled, completed);
		}

		assertThat(runs.getFirst()).isEqualTo(Instant.parse("2026-10-26T00:01:01Z"));
		assertThat(runs.getLast()).isEqualTo(Instant.parse("2026-10-26T03:00:01Z"));
		for (int i = 1; i < runs.size(); i++) {
			assertThat(Duration.between(runs.get(i - 1), runs.get(i))).isEqualTo(MINUTE);
		}
	}

	@Test
	void runsAtTheBoundaryAfterAPollThatTookLongerThanTheInterval() {
		AlignedPollTrigger trigger = new AlignedPollTrigger(MINUTE);
		SimpleTriggerContext context = new SimpleTriggerContext(
				Clock.fixed(Instant.parse("2026-10-26T00:00:30Z"), ZoneOffset.UTC));
		Instant scheduled = Instant.parse("2026-10-26T00:01:01Z");
		context.update(scheduled, scheduled, Instant.parse("2026-10-26T00:03:20Z"));

		// 지나간 경계(02분·03분)에 몰아서 실행하지 않고 끝난 뒤의 다음 경계에 한 번
		assertThat(trigger.nextExecution(context)).isEqualTo(Instant.parse("2026-10-26T00:04:01Z"));
	}

	@Test
	void rejectsAnIntervalThatIsNotPositive() {
		assertThatThrownBy(() -> new AlignedPollTrigger(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AlignedPollTrigger(Duration.ofSeconds(-1)))
			.isInstanceOf(IllegalArgumentException.class);
	}

}
