package com.sentinel.application.auth;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param sessionValidity 세션 유효 기간. 만료 시각은 로그인 시점에 고정되며 사용으로 연장되지 않음
 * @param adminEmail 최초 관리자 계정. 사용자가 한 명도 없을 때만 생성
 * @param adminPassword 최초 관리자 비밀번호. 환경변수로 주입하고 첫 로그인 후 변경 권장
 */
@ConfigurationProperties("sentinel.auth")
public record AuthProperties(
		@DefaultValue("12h") Duration sessionValidity,
		String adminEmail,
		String adminPassword) {

	boolean hasAdmin() {
		return adminEmail != null && !adminEmail.isBlank() && adminPassword != null && !adminPassword.isBlank();
	}

}
