package com.sentinel.domain;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HolidayTest {

	private static final LocalDate HANGUL_DAY = LocalDate.parse("2026-10-09");

	@Test
	void keepsTheNameTrimmed() {
		Holiday holiday = new Holiday(HANGUL_DAY, "  한글날 ", 1L);

		assertThat(holiday.getName()).isEqualTo("한글날");
		assertThat(holiday.getDate()).isEqualTo(HANGUL_DAY);
		assertThat(holiday.getCreatedBy()).isEqualTo(1L);
		assertThat(new Holiday(HANGUL_DAY, "가".repeat(Holiday.MAX_NAME_LENGTH), null).getName()).hasSize(100);
	}

	@Test
	void rejectsMissingDateAndBlankOrLongNames() {
		assertThatThrownBy(() -> new Holiday(null, "한글날", null)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new Holiday(HANGUL_DAY, " ", null)).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("name must not be blank");
		assertThatThrownBy(() -> new Holiday(HANGUL_DAY, "가".repeat(101), null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("name must be at most 100 characters");
	}

}
