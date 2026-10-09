package com.sentinel.api.response;

import java.time.Instant;

/**
 * 재설정 링크 확인 ({@code GET /api/password-resets/{token}})
 * @param userId 새 비밀번호를 설정할 사용자 ({@code PUT /api/users/{userId}/password})
 */
public record PasswordResetResponse(long userId, String email, Instant expiresAt) {
}
