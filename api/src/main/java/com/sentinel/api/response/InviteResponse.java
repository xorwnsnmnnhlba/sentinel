package com.sentinel.api.response;

import java.time.Instant;

/**
 * 초대 링크 확인 ({@code GET /api/invites/{token}})
 */
public record InviteResponse(String email, Instant expiresAt) {
}
