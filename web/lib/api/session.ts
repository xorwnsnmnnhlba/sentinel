import "server-only";

import { cookies } from "next/headers";

/**
 * 로그인 세션 쿠키. 브라우저에는 Next.js 도메인의 httpOnly 쿠키만 노출하고, Spring에는 토큰을 헤더로 전달.
 * <p>
 * 운영(HTTPS)은 __Host- 접두어: 브라우저가 Secure·Path=/·Domain 없음을 강제하여 같은 상위 도메인의 다른 사이트가
 * 이 이름의 쿠키를 심을 수 없음 (심은 쿠키로 공격자 계정에 로그인시키는 cookie tossing 방지).
 * 로컬 http 개발 환경은 Secure 쿠키를 쓸 수 없어 접두어 없이
 */
export const SESSION_COOKIE = process.env.NODE_ENV === "production" ? "__Host-sentinel_session" : "sentinel_session";

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

/**
 * 삭제도 설정과 같은 속성으로 보냄. __Host- 쿠키는 Secure·Path=/가 없는 Set-Cookie를 브라우저가 무시하므로
 * 이름만으로 지우면 로그아웃해도 쿠키가 남음
 */
export async function clearSessionCookie(): Promise<void> {
  (await cookies()).delete({
    name: SESSION_COOKIE,
    httpOnly: true,
    sameSite: "lax",
    secure: process.env.NODE_ENV === "production",
    path: "/",
  });
}
