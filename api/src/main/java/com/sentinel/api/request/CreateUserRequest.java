package com.sentinel.api.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 초대 수락으로 계정 만들기 ({@code POST /api/users}). 이메일·역할은 초대에 따름
 * @param inviteToken 초대 링크의 토큰
 */
public record CreateUserRequest(@NotBlank String inviteToken, @NotBlank String name, @NotBlank String password) {
}
