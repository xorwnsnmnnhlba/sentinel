import { describe, expect, it } from "vitest";

import { parseTheme } from "./theme";

describe("parseTheme", () => {
  it.each(["system", "light", "dark"] as const)("keeps %s", (theme) => {
    expect(parseTheme(theme)).toBe(theme);
  });

  it.each([undefined, "", "DARK", "sepia", 'dark"><script>'])("falls back to system for %s", (value) => {
    expect(parseTheme(value)).toBe("system");
  });
});
