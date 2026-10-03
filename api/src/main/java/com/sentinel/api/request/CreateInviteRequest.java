package com.sentinel.api.request;

import com.sentinel.domain.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * 초대 링크 발급 ({@code POST /api/users/invites})
 */
public record CreateInviteRequest(@Email @NotBlank String email, User.Role role) {
}
