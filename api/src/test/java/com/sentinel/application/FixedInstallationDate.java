package com.sentinel.application;

import java.time.LocalDate;

/**
 * 테스트용 설치일. Flyway 이력(테스트 실행일)과 무관하게 지정
 */
class FixedInstallationDate implements InstallationDate {

	private volatile LocalDate date;

	FixedInstallationDate(LocalDate date) {
		this.date = date;
	}

	void set(LocalDate date) {
		this.date = date;
	}

	@Override
	public LocalDate installedOn() {
		return date;
	}

}
