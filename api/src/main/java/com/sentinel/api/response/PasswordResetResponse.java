package com.sentinel.api.response;

import java.time.Instant;

import com.sentinel.application.IdName;

/**
 * 재설정 링크 확인 ({@code GET /api/password-resets/{token}})
 * @param user 새 비밀번호를 설정할 사용자 {id, name(이메일)} ({@code PUT /api/users/{id}/password})
 */
public record PasswordResetResponse(IdName user, Instant expiresAt) {
}
