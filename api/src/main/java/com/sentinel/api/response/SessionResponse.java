package com.sentinel.api.response;

import com.sentinel.application.auth.AuthenticatedUser;

/**
 * 로그인·초대 수락으로 만든 세션
 *
 * @param token 세션 토큰. 저장하지 않으므로 이 응답에서만 확인 가능. 이후 {@code Authorization: Bearer <토큰>}으로 전달
 */
public record SessionResponse(String token, AuthenticatedUser user) {
}
