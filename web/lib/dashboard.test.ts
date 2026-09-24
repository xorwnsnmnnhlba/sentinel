import { describe, expect, it } from "vitest";

import type { SuiteSummary } from "@/lib/api/types";

import {
  byPassRate,
  delta,
  formatChange,
  formatPercent,
  parseDashboardPeriod,
  previousRange,
  totals,
} from "./dashboard";

function suite(id: number, runs: number, passed: number, total: number, failingRuns = 0): SuiteSummary {
  return {
    suite: { id, name: `suite ${id}` },
    project: { id: 1, name: "project" },
    runs,
    failingRuns,
    results: { total, passed, failed: total - passed, errors: 0 },
    passRate: total === 0 ? null : passed / total,
    lastRun: null,
  };
}

describe("previousRange", () => {
  it("ends the day before the period starts, across month boundaries", () => {
    expect(previousRange("2026-09-18", 7)).toEqual({ from: "2026-09-11", to: "2026-09-17" });
    expect(previousRange("2026-10-01", 30)).toEqual({ from: "2026-09-01", to: "2026-09-30" });
  });
});

describe("parseDashboardPeriod", () => {
  it("accepts 7 or 30 days only", () => {
    expect(parseDashboardPeriod("30")).toBe(30);
    expect(parseDashboardPeriod("90")).toBe(7);
    expect(parseDashboardPeriod(undefined)).toBe(7);
  });
});

describe("totals", () => {
  it("weighs the pass rate by results, not by averaging suites", () => {
    // 스위트 평균이면 (0.5 + 1.0) / 2 = 0.75, 결과 기준이면 91 / 100
    const sum = totals([suite(1, 2, 1, 2, 1), suite(2, 20, 90, 98, 3), suite(3, 0, 0, 0)]);

    expect(sum).toEqual({ runs: 22, failingRuns: 4, results: 100, passed: 91, passRate: 0.91 });
    expect(totals([suite(3, 0, 0, 0)]).passRate).toBeNull();
  });
});

describe("delta", () => {
  const count = (value: number) => `${value}회`;

  it("colors a change by whether higher is better", () => {
    expect(delta(12, 10, count, true)).toEqual({ direction: "up", tone: "good", text: "+2회" });
    expect(delta(12, 10, count, false)).toEqual({ direction: "up", tone: "bad", text: "+2회" });
    expect(delta(8, 10, count, false)).toEqual({ direction: "down", tone: "good", text: "−2회" });
    expect(delta(8, 10, count, null)).toEqual({ direction: "down", tone: "neutral", text: "−2회" });
  });

  it("treats rounding noise as no change and skips missing values", () => {
    expect(delta(0.3, 0.1 + 0.2, count, true)).toEqual({ direction: "flat", tone: "neutral", text: "변화 없음" });
    expect(delta(null, 0.5, count, true)).toBeNull();
  });
});

describe("formatPercent", () => {
  it("never rounds up to a perfect 100%", () => {
    expect(formatPercent(1)).toBe("100%");
    expect(formatPercent(0.9996)).toBe("99.9%");
    expect(formatPercent(0.5)).toBe("50.0%");
    expect(formatPercent(null)).toBe("–");
  });
});

describe("formatChange", () => {
  it("signs the change", () => {
    expect(formatChange(0.42)).toBe("+42%");
    expect(formatChange(-0.25)).toBe("−25%");
    expect(formatChange(0.001)).toBe("0%");
  });
});

describe("byPassRate", () => {
  it("puts the worst suites first and suites without results last", () => {
    const sorted = byPassRate([suite(1, 5, 5, 5), suite(2, 0, 0, 0), suite(3, 4, 2, 4), suite(4, 9, 9, 9)]);

    // 통과율이 같으면 실행이 많은 순
    expect(sorted.map((summary) => summary.suite.id)).toEqual([3, 4, 1, 2]);
  });
});
