package com.sentinel.api.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 초대 수락 ({@code POST /api/invites/{token}}). 계정을 만들고 바로 로그인
 */
public record AcceptInviteRequest(@NotBlank String name, @NotBlank String password) {
}
