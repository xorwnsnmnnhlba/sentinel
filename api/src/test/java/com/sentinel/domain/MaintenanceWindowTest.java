package com.sentinel.domain;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MaintenanceWindowTest {

	private static final Instant NOW = Instant.parse("2026-09-29T01:00:00Z");

	private static final Instant START = NOW.plus(Duration.ofHours(1));

	private static final Instant END = START.plus(Duration.ofHours(2));

	private final Environment environment = new Environment(new Project("billing", null), "dev", "https://gw");

	@Test
	void isActiveFromTheStartUntilJustBeforeTheEnd() {
		MaintenanceWindow window = window(START, END, "GW 배포");

		assertThat(window.isActive(START.minusMillis(1))).isFalse();
		assertThat(window.isActive(START)).isTrue();
		assertThat(window.isActive(END.minusMillis(1))).isTrue();
		assertThat(window.isActive(END)).isFalse();
		// 시작 전에는 예정(끝나지 않음), 끝나면 닫힘
		assertThat(window.isOpen(NOW)).isTrue();
		assertThat(window.isOpen(END)).isFalse();
	}

	@Test
	void keepsTheReasonTrimmedOrNone() {
		assertThat(window(START, END, "  GW 배포 ").getReason()).isEqualTo("GW 배포");
		assertThat(window(START, END, "   ").getReason()).isNull();
		assertThat(window(START, END, "a".repeat(MaintenanceWindow.MAX_REASON_LENGTH)).getReason()).hasSize(200);
	}

	@Test
	void rejectsInvalidPeriodsAndLongReasons() {
		assertThatThrownBy(() -> window(START, START, null)).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("endsAt must be after startsAt");
		assertThatThrownBy(() -> window(NOW.minus(Duration.ofHours(2)), NOW, null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("endsAt must be in the future");
		assertThatThrownBy(() -> window(START, START.plus(Duration.ofDays(7)).plusSeconds(1), null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("a maintenance window can last at most 7 days");
		assertThatThrownBy(() -> window(START, END, "a".repeat(201))).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("reason must be at most 200 characters");
		// 이미 시작한 점검도 등록 가능 (지금부터), 최대 7일까지
		assertThat(window(NOW.minusSeconds(60), NOW.plus(Duration.ofDays(7)).minusSeconds(60), null).isActive(NOW))
			.isTrue();
	}

	@Test
	void changesOnlyWhileNotEnded() {
		MaintenanceWindow window = window(START, END, null);

		window.change(START, END.plus(Duration.ofHours(1)), "연장", NOW);
		assertThat(window.getEndsAt()).isEqualTo(END.plus(Duration.ofHours(1)));
		assertThat(window.getReason()).isEqualTo("연장");

		Instant afterEnd = END.plus(Duration.ofHours(2));
		assertThatThrownBy(() -> window.change(START, afterEnd.plusSeconds(60), null, afterEnd))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("has already ended");
	}

	@Test
	void archivedWindowIsNeitherActiveNorOpen() {
		MaintenanceWindow window = window(NOW.minusSeconds(60), END, null);
		window.archive(NOW);

		assertThat(window.isActive(NOW)).isFalse();
		assertThat(window.isOpen(NOW)).isFalse();
		assertThatThrownBy(() -> window.change(START, END, null, NOW)).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> window.archive(NOW)).isInstanceOf(IllegalStateException.class);
	}

	private MaintenanceWindow window(Instant startsAt, Instant endsAt, String reason) {
		return new MaintenanceWindow(environment, startsAt, endsAt, reason, 1L, NOW);
	}

}
