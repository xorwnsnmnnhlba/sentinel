import { describe, expect, it } from "vitest";

import type { FailureCauseCase } from "@/lib/api/types";

import { CAUSE_LABELS, failingRunsHref, formatShare, GROUP_LABELS, moreCases } from "./failure-cause";

describe("failure cause", () => {
  it("labels every cause and group", () => {
    expect(Object.keys(CAUSE_LABELS)).toHaveLength(8);
    expect(CAUSE_LABELS.ASSERTION_MISMATCH).toBe("응답 내용 불일치");
    expect(GROUP_LABELS.NETWORK).toBe("GW·네트워크");
  });

  it("formats the share of all failures", () => {
    expect(formatShare(12, 30)).toBe("40%");
    expect(formatShare(1, 3)).toBe("33%");
    expect(formatShare(0, 0)).toBe("0%");
  });

  it("links to the failing runs of the case and environment in the period", () => {
    const item: FailureCauseCase = {
      testCase: { id: 12, name: "결제 승인" },
      suite: { id: 3, name: "결제" },
      environment: { id: 7, name: "dev" },
      results: 5,
    };

    expect(failingRunsHref(item, "2026-09-28", "2026-10-04")).toBe(
      "/runs?suiteId=3&environmentId=7&failing=true&from=2026-09-28&to=2026-10-04",
    );
  });

  it("counts the cases that are not shown", () => {
    expect(moreCases(5, 1)).toBe(" 외 4");
    expect(moreCases(1, 1)).toBe("");
  });
});
