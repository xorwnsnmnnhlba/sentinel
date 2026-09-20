import "server-only";

import { cookies } from "next/headers";

/**
 * 로그인 세션 쿠키. 브라우저에는 Next.js 도메인의 httpOnly 쿠키만 노출하고, Spring에는 토큰을 헤더로 전달.
 */
export const SESSION_COOKIE = "sentinel_session";

export async function sessionToken(): Promise<string | undefined> {
  return (await cookies()).get(SESSION_COOKIE)?.value;
}

/**
 * @param maxAgeSeconds 세션 유효 기간. Spring 설정과 맞추지 않아도 만료된 토큰은 401로 처리됨
 */
export async function setSessionCookie(token: string, maxAgeSeconds = 12 * 60 * 60): Promise<void> {
  (await cookies()).set(SESSION_COOKIE, token, {
    httpOnly: true,
    sameSite: "lax",
    // 로컬 http 개발 환경을 위해 운영에서만 secure
    secure: process.env.NODE_ENV === "production",
    path: "/",
    maxAge: maxAgeSeconds,
  });
}

export async function clearSessionCookie(): Promise<void> {
  (await cookies()).delete(SESSION_COOKIE);
}
