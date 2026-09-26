import { NextRequest } from "next/server";
import { describe, expect, it } from "vitest";

import { proxy } from "./proxy";

function request(path: string, loggedIn: boolean, headers: Record<string, string> = {}) {
  return new NextRequest(`https://sentinel.test${path}`, {
    headers: { ...headers, ...(loggedIn ? { cookie: "sentinel_session=token" } : {}) },
  });
}

function nonceOf(policy: string | null): string | undefined {
  return policy?.match(/'nonce-([^']+)'/)?.[1];
}

describe("proxy", () => {
  it("sends visitors without a session to the login page, keeping where they were going", () => {
    const response = proxy(request("/runs?page=2", false));

    expect(response.status).toBe(307);
    expect(response.headers.get("location")).toBe("https://sentinel.test/login?from=%2Fruns%3Fpage%3D2");
  });

  it.each(["/login", "/invites/abc", "/password-resets/abc"])(
    "lets %s open without a session but still applies the policy",
    (path) => {
      const response = proxy(request(path, false));

      expect(response.headers.get("location")).toBeNull();
      expect(response.headers.get("content-security-policy")).toContain("'strict-dynamic'");
    },
  );

  it("does not treat look-alike paths as public", () => {
    expect(proxy(request("/login-help", false)).status).toBe(307);
    expect(proxy(request("/invitesx", false)).status).toBe(307);
  });

  it("uses a fresh nonce per request and passes the same policy to the renderer", () => {
    const first = proxy(request("/", true));
    const second = proxy(request("/", true));

    const policy = first.headers.get("content-security-policy");
    expect(nonceOf(policy)).toBeDefined();
    expect(nonceOf(policy)).not.toBe(nonceOf(second.headers.get("content-security-policy")));
    // Next.js가 렌더링 중 nonce를 읽는 요청 헤더에도 같은 정책
    expect(first.headers.get("x-middleware-request-content-security-policy")).toBe(policy);
  });

  it("still requires a session for link prefetches but skips the policy", () => {
    const prefetch = { "next-router-prefetch": "1" };

    expect(proxy(request("/runs", false, prefetch)).status).toBe(307);
    expect(proxy(request("/runs", true, prefetch)).headers.get("content-security-policy")).toBeNull();
    expect(proxy(request("/runs", true, { purpose: "prefetch" })).headers.get("content-security-policy")).toBeNull();
  });
});
