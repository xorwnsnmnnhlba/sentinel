package com.sentinel.application;

import java.time.LocalDate;

/**
 * Sentinel 설치일 (스케줄 시간대 기준). 설치 전 기간을 다루는 알림을 보내지 않는 데 사용
 */
interface InstallationDate {

	/** @return 설치일. 알 수 없으면 null */
	LocalDate installedOn();

}
