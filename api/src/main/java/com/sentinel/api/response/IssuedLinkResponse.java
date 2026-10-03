package com.sentinel.api.response;

import java.time.Instant;

/**
 * 초대·비밀번호 재설정 링크 발급
 *
 * @param token 링크에 담을 토큰. 저장하지 않으므로 이 응답에서만 확인 가능
 */
public record IssuedLinkResponse(String token, Instant expiresAt) {
}
