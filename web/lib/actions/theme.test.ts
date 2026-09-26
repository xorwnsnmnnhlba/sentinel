import { describe, expect, it } from "vitest";

import type { Theme } from "@/lib/theme";
import { testCookieOptions, testCookies } from "@/test/cookies";

import { setTheme } from "./theme";

describe("setTheme", () => {
  it("remembers the theme for a year in a cookie scripts cannot read", async () => {
    await setTheme("dark");

    expect(testCookies.get("sentinel_theme")).toBe("dark");
    expect(testCookieOptions.get("sentinel_theme")).toMatchObject({
      httpOnly: true,
      sameSite: "lax",
      path: "/",
      maxAge: 365 * 24 * 60 * 60,
    });
  });

  it("stores system for values outside the allowed themes", async () => {
    // Server Action 인자는 브라우저에서 임의로 보낼 수 있음
    await setTheme("sepia" as Theme);

    expect(testCookies.get("sentinel_theme")).toBe("system");
  });
});
