package com.sentinel.application;

import java.time.LocalDate;

import lombok.RequiredArgsConstructor;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.springframework.stereotype.Component;

/**
 * 첫 DB 마이그레이션을 적용한 날을 설치일로 사용 (Flyway 이력). 바뀌지 않는 값이라 처음 읽은 뒤 보관
 */
@Component
@RequiredArgsConstructor
class FlywayInstallationDate implements InstallationDate {

	private final Flyway flyway;

	private final ScheduleProperties scheduleProperties;

	private volatile LocalDate cached;

	@Override
	public LocalDate installedOn() {
		if (cached == null) {
			MigrationInfo[] applied = flyway.info().applied();
			if (applied.length > 0 && applied[0].getInstalledOn() != null) {
				cached = applied[0].getInstalledOn().toInstant().atZone(scheduleProperties.zone()).toLocalDate();
			}
		}
		return cached;
	}

}
