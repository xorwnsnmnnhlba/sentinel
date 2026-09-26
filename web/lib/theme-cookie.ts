import "server-only";

import { cookies } from "next/headers";

import { parseTheme, THEME_COOKIE, type Theme } from "@/lib/theme";

/** 요청 쿠키의 테마. 없거나 잘못된 값이면 system */
export async function themeFromCookie(): Promise<Theme> {
  return parseTheme((await cookies()).get(THEME_COOKIE)?.value);
}
