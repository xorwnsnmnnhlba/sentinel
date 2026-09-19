import { describe, expect, it } from "vitest";

import { formatDateTime, formatElapsed, formatRunDuration, prettyBody } from "./format";

describe("formatDateTime", () => {
  it("shows KST regardless of the runtime time zone", () => {
    expect(formatDateTime("2026-09-21T00:10:00Z")).toBe("2026-09-21 09:10:00");
    // UTC 15시 이후는 KST 다음 날
    expect(formatDateTime("2026-09-20T15:30:05Z")).toBe("2026-09-21 00:30:05");
  });
});

describe("formatElapsed", () => {
  it.each([
    [null, "-"],
    [undefined, "-"],
    [0, "0ms"],
    [999, "999ms"],
    [1000, "1.0s"],
    [12_345, "12.3s"],
  ])("%s → %s", (ms, text) => {
    expect(formatElapsed(ms)).toBe(text);
  });
});

describe("formatRunDuration", () => {
  it("shows dash while running", () => {
    expect(formatRunDuration("2026-09-20T00:00:00Z", null)).toBe("-");
  });

  it("uses finished minus started", () => {
    expect(formatRunDuration("2026-09-20T00:00:00Z", "2026-09-20T00:00:01.500Z")).toBe("1.5s");
  });
});

describe("prettyBody", () => {
  it("indents JSON and keeps other text as is", () => {
    expect(prettyBody('{"a":1}')).toBe('{\n  "a": 1\n}');
    expect(prettyBody("<html>")).toBe("<html>");
    expect(prettyBody(null)).toBe("");
  });
});
