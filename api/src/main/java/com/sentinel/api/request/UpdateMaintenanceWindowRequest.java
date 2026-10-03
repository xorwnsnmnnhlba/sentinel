package com.sentinel.api.request;

import java.time.Instant;

/**
 * 점검 시간 부분 변경 (연장·단축·사유). null 필드는 유지, 빈 사유는 사유 삭제
 */
public record UpdateMaintenanceWindowRequest(Instant startsAt, Instant endsAt, String reason) {
}
