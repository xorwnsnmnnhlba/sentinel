/**
 * 화면 테마. system은 운영체제 설정(prefers-color-scheme)을 따름
 * 고르지 않았으면 다크 (관제 화면 기본). 쿠키 값은 사용자가 바꿀 수 있으므로 알 수 없는 값도 다크로 처리
 */
export const THEMES = ["system", "light", "dark"] as const;

export type Theme = (typeof THEMES)[number];

export const THEME_COOKIE = "sentinel_theme";

export const DEFAULT_THEME: Theme = "dark";

export function parseTheme(value: string | undefined): Theme {
  return THEMES.find((theme) => theme === value) ?? DEFAULT_THEME;
}
