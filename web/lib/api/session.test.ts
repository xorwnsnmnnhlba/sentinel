import { afterEach, describe, expect, it, vi } from "vitest";

import { testCookieOptions, testCookies } from "@/test/cookies";

/** NODE_ENV에 따라 정해지는 쿠키 이름을 확인하려고 모듈을 다시 읽음 */
async function sessionModule(env: string) {
  vi.stubEnv("NODE_ENV", env);
  vi.resetModules();
  return import("@/lib/api/session");
}

describe("session cookie", () => {
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.resetModules();
  });

  it("uses a __Host- cookie in production so sibling sites cannot plant one", async () => {
    const { SESSION_COOKIE, setSessionCookie, sessionToken, clearSessionCookie } = await sessionModule("production");

    await setSessionCookie("token-abc");

    expect(SESSION_COOKIE).toBe("__Host-sentinel_session");
    expect(testCookies.get("__Host-sentinel_session")).toBe("token-abc");
    // __Host- 쿠키는 Secure·Path=/·Domain 없음이어야 브라우저가 받음
    expect(testCookieOptions.get("__Host-sentinel_session")).toMatchObject({ secure: true, path: "/", httpOnly: true });
    expect(testCookieOptions.get("__Host-sentinel_session")).not.toHaveProperty("domain");
    expect(await sessionToken()).toBe("token-abc");
    // 접두어 없는 쿠키(다른 사이트가 심을 수 있는 이름)는 읽지 않음
    testCookies.clear();
    testCookies.set("sentinel_session", "planted");
    expect(await sessionToken()).toBeUndefined();

    testCookies.set("__Host-sentinel_session", "token-abc");
    testCookieOptions.clear();
    await clearSessionCookie();
    expect(testCookies.has("__Host-sentinel_session")).toBe(false);
    // 삭제도 Secure·Path=/로 보내야 브라우저가 받음 (이름만이면 무시되어 로그아웃 뒤에도 쿠키가 남음)
    expect(testCookieOptions.get("__Host-sentinel_session")).toMatchObject({ secure: true, path: "/" });
  });

  it("keeps the plain name without Secure for local http development", async () => {
    const { SESSION_COOKIE, setSessionCookie } = await sessionModule("development");

    await setSessionCookie("token-abc");

    expect(SESSION_COOKIE).toBe("sentinel_session");
    expect(testCookieOptions.get("sentinel_session")).toMatchObject({ secure: false, path: "/" });
  });
});
