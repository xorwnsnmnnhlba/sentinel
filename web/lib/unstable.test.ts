import { describe, expect, it } from "vitest";

import type { UnstableCase } from "@/lib/api/types";

import { unstableByCase, unstableDetail, unstableReason } from "./unstable";

function unstable(caseId: number, environment: string, failureEpisodes: number, retriedRuns: number): UnstableCase {
  return {
    testCase: { id: caseId, name: `case-${caseId}` },
    suite: { id: 1, name: "suite" },
    environment: { id: environment.length, name: environment },
    runs: 10,
    failures: failureEpisodes,
    failureEpisodes,
    retriedRuns,
  };
}

describe("unstableReason", () => {
  it("names only what makes the case unstable", () => {
    expect(unstableReason(unstable(1, "dev", 3, 0))).toBe("실패 구간 3회");
    expect(unstableReason(unstable(1, "dev", 1, 2))).toBe("재시도 2회");
    expect(unstableReason(unstable(1, "dev", 2, 1))).toBe("실패 구간 2회 · 재시도 1회");
  });
});

describe("unstableByCase", () => {
  it("groups the environments of each case", () => {
    const grouped = unstableByCase([unstable(1, "dev", 2, 0), unstable(2, "dev", 2, 0), unstable(1, "staging", 0, 3)]);

    expect([...grouped.keys()]).toEqual([1, 2]);
    expect(unstableDetail(grouped.get(1)!)).toBe("dev: 실패 구간 2회, staging: 재시도 3회");
  });
});
