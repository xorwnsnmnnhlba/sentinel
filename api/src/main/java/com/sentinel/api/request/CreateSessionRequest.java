package com.sentinel.api.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 로그인 ({@code POST /api/sessions})
 */
public record CreateSessionRequest(@NotBlank String email, @NotBlank String password) {
}
