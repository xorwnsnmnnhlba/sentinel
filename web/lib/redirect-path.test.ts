import { describe, expect, it } from "vitest";

import { safeRedirectPath } from "./redirect-path";

describe("safeRedirectPath", () => {
  it.each([
    ["/runs?page=2", "/runs?page=2"],
    ["/suites/3?days=30#response-times", "/suites/3?days=30#response-times"],
    // 경로 안의 ..는 같은 사이트 안에서 정리
    ["/runs/../projects", "/projects"],
    // 인코딩된 \는 경로 문자로 남아 같은 사이트
    ["/%5Cevil.example", "/%5Cevil.example"],
  ])("keeps the same-site path %s", (value, expected) => {
    expect(safeRedirectPath(value)).toBe(expected);
  });

  it.each([
    "https://evil.example",
    "//evil.example",
    "/\\evil.example",
    "\\\\evil.example",
    "/\t/evil.example",
    "/\r\n/evil.example",
    "javascript:alert(1)",
    "runs",
    "",
    null,
    42,
  ])("falls back to home for %j", (value) => {
    expect(safeRedirectPath(value)).toBe("/");
  });
});
