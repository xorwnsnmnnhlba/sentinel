package com.sentinel.domain;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestScheduleTest {

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	/** 2026-09-18 09:00 KST */
	private static final Instant NINE_AM_KST = Instant.parse("2026-09-18T00:00:00Z");

	private final Project project = new Project("billing", null);

	private final TestSuite suite = new TestSuite(project, "charge", null);

	private final Environment environment = new Environment(project, "dev", "https://gw");

	@Test
	void computesNextRunInConfiguredZone() {
		assertThat(schedule("10 9 * * *", SEOUL).getNextRunAt()).isEqualTo("2026-09-18T00:10:00Z");
		// 같은 cron을 UTC로 해석하면 9시간 어긋남
		assertThat(schedule("10 9 * * *", ZoneOffset.UTC).getNextRunAt()).isEqualTo("2026-09-18T09:10:00Z");
	}

	@Test
	void triggersOnceAndMovesToNextSlotAfterNowWhenRunsWereMissed() {
		TestSchedule schedule = schedule("10 9 * * *", SEOUL);
		Instant threeDaysLater = Instant.parse("2026-09-21T03:00:00Z");

		assertThat(schedule.isDue(threeDaysLater)).isTrue();
		schedule.markTriggered(threeDaysLater, SEOUL);

		assertThat(schedule.getLastTriggeredAt()).isEqualTo(threeDaysLater);
		assertThat(schedule.getNextRunAt()).isEqualTo("2026-09-22T00:10:00Z");
		assertThat(schedule.isDue(threeDaysLater)).isFalse();
	}

	@Test
	void rejectsTriggerBeforeDue() {
		TestSchedule schedule = schedule("10 9 * * *", SEOUL);

		assertThatThrownBy(() -> schedule.markTriggered(NINE_AM_KST, SEOUL)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void disableClearsNextRunAndEnableRecomputesFromNow() {
		TestSchedule schedule = schedule("30 12 * * *", SEOUL);

		schedule.disable();
		assertThat(schedule.getNextRunAt()).isNull();
		assertThat(schedule.isDue(Instant.parse("2030-01-01T00:00:00Z"))).isFalse();

		schedule.enable(Instant.parse("2026-09-19T04:00:00Z"), SEOUL);
		assertThat(schedule.getNextRunAt()).isEqualTo("2026-09-20T03:30:00Z");
	}

	@Test
	void changingCronRecomputesNextRunOnlyWhenEnabled() {
		TestSchedule schedule = schedule("10 9 * * *", SEOUL);

		schedule.changeCron("30 17 * * *", NINE_AM_KST, SEOUL);
		assertThat(schedule.getCron()).isEqualTo("30 17 * * *");
		assertThat(schedule.getNextRunAt()).isEqualTo("2026-09-18T08:30:00Z");

		schedule.disable();
		schedule.changeCron("0 12 * * *", NINE_AM_KST, SEOUL);
		assertThat(schedule.getCron()).isEqualTo("0 12 * * *");
		assertThat(schedule.getNextRunAt()).isNull();
	}

	@ParameterizedTest
	@ValueSource(strings = { "10 9 * *", "0 0 30 2 *" })
	void rejectsInvalidCronChangeAndKeepsPrevious(String cron) {
		TestSchedule schedule = schedule("10 9 * * *", SEOUL);
		schedule.disable();

		assertThatThrownBy(() -> schedule.changeCron(cron, NINE_AM_KST, SEOUL))
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(schedule.getCron()).isEqualTo("10 9 * * *");
	}

	@Test
	void normalizesWhitespace() {
		assertThat(schedule("  30   17 * *  1-5 ", SEOUL).getCron()).isEqualTo("30 17 * * 1-5");
	}

	@ParameterizedTest
	@ValueSource(strings = { "0 10 9 * * *", "10 9 * *", "61 9 * * *", "not a cron", "0 0 30 2 *" })
	void rejectsInvalidOrNeverFiringCron(String cron) {
		assertThatThrownBy(() -> schedule(cron, SEOUL)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void changesEnvironmentWithinTheSameProject() {
		TestSchedule schedule = schedule("10 9 * * *", SEOUL);
		Environment staging = new Environment(project, "staging", "https://gw-stg");

		schedule.changeEnvironment(staging);

		assertThat(schedule.getEnvironment()).isSameAs(staging);
		// 환경만 바뀌고 실행 시각은 유지
		assertThat(schedule.getNextRunAt()).isEqualTo("2026-09-18T00:10:00Z");
	}

	@Test
	void rejectsEnvironmentChangeToAnotherProject() {
		TestSchedule schedule = schedule("10 9 * * *", SEOUL);
		Environment other = new Environment(new Project("other", null), "dev", "https://gw");

		assertThatThrownBy(() -> schedule.changeEnvironment(other)).isInstanceOf(IllegalArgumentException.class);
		assertThat(schedule.getEnvironment()).isSameAs(environment);
	}

	@Test
	void rejectsEnvironmentOfAnotherProject() {
		Environment other = new Environment(new Project("other", null), "dev", "https://gw");

		assertThatThrownBy(() -> new TestSchedule(suite, other, "0 9 * * *", NINE_AM_KST, SEOUL))
				.isInstanceOf(IllegalArgumentException.class);
	}

	private TestSchedule schedule(String cron, ZoneId zone) {
		return new TestSchedule(suite, environment, cron, NINE_AM_KST, zone);
	}

}
