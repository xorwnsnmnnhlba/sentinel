package com.sentinel.api;

import com.sentinel.application.SystemStatusService;
import com.sentinel.application.SystemStatusService.SystemStatus;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 시스템 상태 (관리자)
 */
@RestController
@Tag(name = "시스템")
@RequestMapping("/api")
@RequiredArgsConstructor
class SystemStatusController {

	private final SystemStatusService systemStatusService;

	private final CurrentUser currentUser;

	/**
	 * 서버 설정·버전 상태 (관리자). 버전·빌드 시각, DB 마이그레이션, 암호화 키 상태와 풀 수 없는 시크릿, 알림·생존 신호 설정 여부, 보관 기간.
	 * 주소·키 값은 포함하지 않음
	 */
	@GetMapping("/system-status")
	SystemStatus status() {
		currentUser.requireAdmin();
		return systemStatusService.status();
	}

}
