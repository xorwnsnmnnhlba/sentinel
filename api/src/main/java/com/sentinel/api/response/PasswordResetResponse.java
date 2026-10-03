package com.sentinel.api.response;

import java.time.Instant;

/**
 * 재설정 링크 확인 ({@code GET /api/password-resets/{token}})
 */
public record PasswordResetResponse(String email, Instant expiresAt) {
}
