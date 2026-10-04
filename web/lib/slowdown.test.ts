import { describe, expect, it } from "vitest";

import type { Slowdown } from "@/lib/api/types";

import { formatIncrease, formatP95Change, responseTimesHref, slowdownDetail, slowdownsByCase } from "./slowdown";

function slowdown(caseId: number, environment: string, baselineP95: number, detectedP95: number): Slowdown {
  return {
    id: caseId * 10 + environment.length,
    testCase: { id: caseId, name: `case-${caseId}` },
    suite: { id: 3, name: "결제" },
    project: { id: 1, name: "결제 서비스" },
    environment: { id: environment.length, name: environment },
    baselineP95,
    detectedP95,
    increase: (detectedP95 - baselineP95) / baselineP95,
    detectedAt: "2026-10-04T02:05:00Z",
  };
}

describe("slowdown", () => {
  it("formats the increase and the p95 change at detection", () => {
    expect(formatIncrease(0.906)).toBe("+91%");
    expect(formatIncrease(3)).toBe("+300%");
    expect(formatP95Change(slowdown(1, "dev", 320, 610))).toBe("320ms → 610ms");
  });

  it("links to the response time trend of the case and environment", () => {
    expect(responseTimesHref(slowdown(12, "dev", 320, 610))).toBe("/suites/3?environmentId=3&caseId=12#response-times");
  });

  it("groups by case and describes each environment", () => {
    const grouped = slowdownsByCase([
      slowdown(1, "dev", 320, 610),
      slowdown(2, "dev", 100, 200),
      slowdown(1, "staging", 200, 400),
    ]);

    expect([...grouped.keys()]).toEqual([1, 2]);
    expect(slowdownDetail(grouped.get(1)!)).toBe("dev: 320ms → 610ms (+91%), staging: 200ms → 400ms (+100%)");
  });
});
