package com.sentinel.application;

import java.time.LocalDate;

import com.sentinel.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 설치일은 첫 마이그레이션 적용일 (테스트 DB는 이번 실행 또는 재사용한 컨테이너에서 이전에 적용)
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class FlywayInstallationDateTest {

	@Autowired
	private FlywayInstallationDate installation;

	@Autowired
	private ScheduleProperties scheduleProperties;

	@Test
	void readsTheDateTheFirstMigrationWasApplied() {
		LocalDate installedOn = installation.installedOn();

		assertThat(installedOn).isNotNull().isBeforeOrEqualTo(LocalDate.now(scheduleProperties.zone()));
		assertThat(installation.installedOn()).isSameAs(installedOn);
	}

}
