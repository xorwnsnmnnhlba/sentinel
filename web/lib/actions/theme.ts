"use server";

import { cookies } from "next/headers";

import { parseTheme, THEME_COOKIE, type Theme } from "@/lib/theme";

/**
 * 테마 저장. 화면은 브라우저에서 먼저 바꾸고, 다음 요청부터 서버가 같은 테마로 렌더링하도록 쿠키에 보관 (1년)
 * 로그인 여부와 무관한 표시 설정이라 세션과 별도 쿠키
 */
export async function setTheme(theme: Theme): Promise<void> {
  (await cookies()).set(THEME_COOKIE, parseTheme(theme), {
    httpOnly: true,
    sameSite: "lax",
    secure: process.env.NODE_ENV === "production",
    path: "/",
    maxAge: 365 * 24 * 60 * 60,
  });
}
