package com.sentinel.api.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 재설정 링크로 새 비밀번호 설정 ({@code POST /api/password-resets/{token}})
 */
public record ResetPasswordRequest(@NotBlank String password) {
}
