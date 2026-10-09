import { describe, expect, it } from "vitest";

import type { CaseResponseTime } from "@/lib/api/types";

import {
  formatMs,
  isolatedIndexes,
  kstDateRange,
  linePath,
  nearestIndex,
  niceTicks,
  parsePeriod,
  slowestCase,
  tickIndexes,
} from "./response-times";

describe("kstDateRange", () => {
  it("counts days up to today in KST whatever the server time zone", () => {
    // UTC 9/30 16:00 = KST 10/1 01:00
    expect(kstDateRange(7, new Date("2026-09-30T16:00:00Z"))).toEqual({ from: "2026-09-25", to: "2026-10-01" });
    expect(kstDateRange(1, new Date("2026-09-30T14:59:59Z"))).toEqual({ from: "2026-09-30", to: "2026-09-30" });
  });
});

describe("parsePeriod", () => {
  it.each([
    ["30", 30],
    ["90", 90],
    ["14", 7],
    [undefined, 7],
  ])("reads %s as %i days", (value, days) => {
    expect(parsePeriod(value)).toBe(days);
  });
});

describe("slowestCase", () => {
  const stats = (caseId: number, p95: number | null): CaseResponseTime => ({
    testCase: { id: caseId, name: `case ${caseId}` },
    samples: p95 === null ? 0 : 1,
    p50: p95,
    p95,
    max: p95,
  });

  it("picks the case with the highest p95 and skips cases without results", () => {
    expect(slowestCase([stats(1, 120), stats(2, null), stats(3, 900), stats(4, 300)])?.testCase.id).toBe(3);
    expect(slowestCase([stats(1, null)])).toBeNull();
  });
});

describe("niceTicks", () => {
  it.each([
    [880, [0, 500, 1000]],
    [340, [0, 100, 200, 300, 400]],
    [12, [0, 5, 10, 15]],
    [2, [0, 1, 2]],
    [0, [0, 1]],
  ])("covers %i with clean steps", (max, ticks) => {
    expect(niceTicks(max)).toEqual(ticks);
  });
});

describe("linePath", () => {
  const x = (index: number) => index * 10;
  const y = (value: number) => value;

  it("breaks the line on days without results", () => {
    expect(linePath([5, 6, null, 8, 9], x, y)).toBe("M0,5L10,6M30,8L40,9");
    expect(linePath([null, null], x, y)).toBe("");
  });

  it("marks points that have no neighbour to draw a line to", () => {
    expect(isolatedIndexes([5, null, 7, null, 8, 9, null])).toEqual([0, 2]);
  });
});

describe("nearestIndex", () => {
  it("snaps the pointer to the closest day inside the plot", () => {
    // 5일, 플롯 왼쪽 50, 너비 400 → 100px 간격
    expect(nearestIndex(50, 5, 50, 400)).toBe(0);
    expect(nearestIndex(199, 5, 50, 400)).toBe(1);
    expect(nearestIndex(10, 5, 50, 400)).toBe(0);
    expect(nearestIndex(999, 5, 50, 400)).toBe(4);
    expect(nearestIndex(300, 1, 50, 400)).toBe(0);
  });
});

describe("tickIndexes", () => {
  it("keeps the first and last day", () => {
    expect(tickIndexes(3)).toEqual([0, 1, 2]);
    expect(tickIndexes(30)).toEqual([0, 7, 15, 22, 29]);
    // 좁은 화면은 라벨 수를 줄여 끝의 두 날짜가 붙지 않도록 함
    expect(tickIndexes(7, 3)).toEqual([0, 3, 6]);
  });
});

describe("formatMs", () => {
  it("formats milliseconds and missing values", () => {
    expect(formatMs(1234)).toBe("1,234ms");
    expect(formatMs(null)).toBe("–");
  });
});
