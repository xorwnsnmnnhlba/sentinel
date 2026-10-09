package com.sentinel.application;

import java.time.Instant;

import com.sentinel.domain.MaintenanceWindow;

/**
 * @param active 지금 점검 중 (스케줄 실행을 건너뜀). false면 예정
 * @param reason 점검 사유. 없으면 null
 * @param createdBy 등록한 사용자 {id, name(이메일)}. 알 수 없으면 null
 */
public record MaintenanceWindowItem(long id, IdName environment, IdName project, Instant startsAt, Instant endsAt,
		String reason, boolean active, IdName createdBy) {

	static MaintenanceWindowItem of(MaintenanceWindow window, IdName createdBy, Instant now) {
		return new MaintenanceWindowItem(window.getId(),
				IdName.of(window.getEnvironment()), IdName.of(window.getEnvironment().getProject()),
				window.getStartsAt(), window.getEndsAt(),
				window.getReason(), window.isActive(now), createdBy);
	}

}
