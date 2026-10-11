package com.sentinel.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 설치일은 첫 마이그레이션 적용일. 마지막(업데이트) 적용일을 쓰면 업데이트마다 지난 요약을 건너뜀
 */
class FlywayInstallationDateUnitTest {

	private final ScheduleProperties scheduleProperties = mock(ScheduleProperties.class);

	@Test
	void usesTheFirstAppliedMigrationInTheScheduleZone() {
		when(scheduleProperties.zone()).thenReturn(ZoneId.of("Asia/Seoul"));
		// 2026-01-04 15:30 UTC = 01-05 00:30 KST (시간대에 따라 날짜가 바뀌는 시각)
		Flyway flyway = flywayWith(migration(Instant.parse("2026-01-04T15:30:00Z")),
				migration(Instant.parse("2026-10-01T00:00:00Z")));

		assertThat(new FlywayInstallationDate(flyway, scheduleProperties).installedOn())
			.isEqualTo(LocalDate.of(2026, 1, 5));
	}

	@Test
	void isUnknownBeforeAnyMigration() {
		when(scheduleProperties.zone()).thenReturn(ZoneId.of("Asia/Seoul"));

		assertThat(new FlywayInstallationDate(flywayWith(), scheduleProperties).installedOn()).isNull();
	}

	private static Flyway flywayWith(MigrationInfo... applied) {
		Flyway flyway = mock(Flyway.class);
		MigrationInfoService info = mock(MigrationInfoService.class);
		when(info.applied()).thenReturn(applied);
		when(flyway.info()).thenReturn(info);
		return flyway;
	}

	private static MigrationInfo migration(Instant installedOn) {
		MigrationInfo migration = mock(MigrationInfo.class);
		when(migration.getInstalledOn()).thenReturn(Date.from(installedOn));
		return migration;
	}

}
