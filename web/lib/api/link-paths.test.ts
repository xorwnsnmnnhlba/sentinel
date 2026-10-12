import { describe, expect, it } from "vitest";

import { inviteApiPath, passwordResetApiPath } from "@/lib/api/link-paths";

describe("link API paths", () => {
  it("keeps a normal token as is", () => {
    expect(inviteApiPath("abc-DEF_123")).toBe("/api/invites/abc-DEF_123");
    expect(passwordResetApiPath("abc-DEF_123")).toBe("/api/password-resets/abc-DEF_123");
  });

  it("keeps a decoded token inside one path segment", () => {
    // /invites/..%2Fusers%2F1 → 토큰 "../users/1". 인코딩하지 않으면 /api/users/1로 정리되어 다른 API 호출
    expect(inviteApiPath("../users/1")).toBe("/api/invites/..%2Fusers%2F1");
    expect(passwordResetApiPath("x?admin=1#y")).toBe("/api/password-resets/x%3Fadmin%3D1%23y");
    expect(new URL(inviteApiPath("../users/1"), "http://api.test").pathname).toBe("/api/invites/..%2Fusers%2F1");
  });
});
