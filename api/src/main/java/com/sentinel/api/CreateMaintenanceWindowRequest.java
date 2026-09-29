package com.sentinel.api;

import java.time.Instant;

import jakarta.validation.constraints.NotNull;

/**
 * @param startsAt 시작 (포함). ISO-8601 (예: {@code 2026-09-29T10:00:00+09:00})
 * @param endsAt 끝 (미포함). 시작 뒤, 현재 이후, 시작부터 최대 7일
 * @param reason 점검 사유 (선택, 200자 이하)
 */
record CreateMaintenanceWindowRequest(@NotNull Long environmentId, @NotNull Instant startsAt,
		@NotNull Instant endsAt, String reason) {
}
