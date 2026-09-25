const BASE = "http://sentinel.invalid";

/**
 * 로그인 후 돌아갈 주소. 같은 사이트 경로만 허용하고 그 외는 홈
 *
 * 브라우저는 `\`를 `/`로 바꾸고 탭·줄바꿈을 지운 뒤 주소를 해석하므로 `/\evil.example`, `/\t/evil.example`이
 * `//evil.example`(다른 사이트)로 이동함. 문자열 앞부분만 보지 않고 실제로 해석한 결과가 같은 출처인지 확인
 */
export function safeRedirectPath(value: unknown): string {
  if (typeof value !== "string" || !value.startsWith("/") || value.startsWith("//")) {
    return "/";
  }
  if (value.includes("\\") || /[\u0000-\u001f\u007f]/.test(value)) {
    return "/";
  }
  try {
    const url = new URL(value, BASE);
    return url.origin === BASE ? `${url.pathname}${url.search}${url.hash}` : "/";
  } catch {
    return "/";
  }
}
