package com.sentinel.api.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 재설정 링크로 새 비밀번호 설정 ({@code PUT /api/users/{userId}/password})
 * @param resetToken 재설정 링크의 토큰
 */
public record ResetPasswordRequest(@NotBlank String resetToken, @NotBlank String password) {
}
