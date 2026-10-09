package com.sentinel.api.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 본인 비밀번호 변경 ({@code PUT /api/users/me/password})
 */
public record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank String newPassword) {
}
