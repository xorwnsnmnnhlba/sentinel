import { describe, expect, it } from "vitest";

import type { RunChange, RunResult } from "@/lib/api/types";

import { countChanges, isShownChange } from "./run-change";

function result(change: RunChange | null): RunResult {
  return { change } as RunResult;
}

describe("countChanges", () => {
  it("counts shown changes in display order and leaves out empty ones", () => {
    const results = ["RECOVERED", "NEWLY_FAILING", "UNCHANGED", "NEWLY_FAILING", "NEW", null].map((change) =>
      result(change as RunChange | null),
    );

    expect(countChanges(results)).toEqual([
      { change: "NEWLY_FAILING", count: 2 },
      { change: "RECOVERED", count: 1 },
      { change: "NEW", count: 1 },
    ]);
  });

  it("finds nothing to show when every case kept passing", () => {
    expect(countChanges([result("UNCHANGED"), result("UNCHANGED")])).toEqual([]);
  });
});

describe("isShownChange", () => {
  it("hides unchanged cases and runs without a comparison", () => {
    expect(isShownChange("STILL_FAILING")).toBe(true);
    expect(isShownChange("UNCHANGED")).toBe(false);
    expect(isShownChange(null)).toBe(false);
  });
});
