import { describe, expect, it } from "vitest";

import { contentSecurityPolicy, createNonce } from "./csp";

function directives(policy: string): Record<string, string> {
  return Object.fromEntries(
    policy.split("; ").map((directive) => {
      const [name, ...values] = directive.split(" ");
      return [name, values.join(" ")];
    }),
  );
}

describe("contentSecurityPolicy", () => {
  it("allows only nonce scripts and blocks everything else in production", () => {
    const policy = directives(contentSecurityPolicy("abc123", false));

    expect(policy["script-src"]).toBe("'self' 'nonce-abc123' 'strict-dynamic'");
    expect(policy["style-src-elem"]).toBe("'self' 'nonce-abc123'");
    // 차트·막대·Radix가 쓰는 style="" 속성만 허용
    expect(policy["style-src-attr"]).toBe("'unsafe-inline'");
    expect(policy["object-src"]).toBe("'none'");
    expect(policy["frame-ancestors"]).toBe("'none'");
    expect(policy["base-uri"]).toBe("'self'");
    expect(policy["form-action"]).toBe("'self'");
    expect(policy["connect-src"]).toBe("'self'");
    // 운영에는 eval·인라인 스크립트 허용 없음
    expect(Object.values(policy).join(" ")).not.toMatch(/unsafe-eval|script-src[^;]*unsafe-inline/);
    // http://localhost:3000 직접 접속을 깨뜨리지 않도록 미사용
    expect(policy).not.toHaveProperty("upgrade-insecure-requests");
  });

  it("relaxes only what the development server needs", () => {
    const policy = directives(contentSecurityPolicy("abc123", true));

    expect(policy["script-src"]).toBe("'self' 'nonce-abc123' 'strict-dynamic' 'unsafe-eval'");
    expect(policy["style-src-elem"]).toBe("'self' 'unsafe-inline'");
    expect(policy["connect-src"]).toBe("'self' ws: wss:");
  });
});

describe("createNonce", () => {
  it("returns an unpredictable 16-byte value each time", () => {
    const nonces = new Set(Array.from({ length: 100 }, () => createNonce()));

    expect(nonces.size).toBe(100);
    for (const nonce of nonces) {
      expect(nonce).toMatch(/^[A-Za-z0-9+/]{22}==$/);
    }
  });
});
