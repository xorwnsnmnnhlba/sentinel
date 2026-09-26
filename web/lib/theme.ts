/**
 * 화면 테마. system은 운영체제 설정(prefers-color-scheme)을 따름
 * 쿠키 값은 사용자가 바꿀 수 있으므로 알 수 없는 값은 system으로 처리
 */
export const THEMES = ["system", "light", "dark"] as const;

export type Theme = (typeof THEMES)[number];

export const THEME_COOKIE = "sentinel_theme";

export function parseTheme(value: string | undefined): Theme {
  return THEMES.find((theme) => theme === value) ?? "system";
}
