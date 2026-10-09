package com.sentinel.api.request;

import jakarta.validation.constraints.NotNull;

/**
 * 비밀번호 재설정 링크 발급 ({@code POST /api/password-resets})
 */
public record CreatePasswordResetRequest(@NotNull Long userId) {
}
