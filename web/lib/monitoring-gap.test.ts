import { describe, expect, it } from "vitest";

import type { MonitoringGap } from "@/lib/api/types";

import { formatGapDuration, gapDuration, totalGapDuration } from "./monitoring-gap";

function gap(startedAt: string, endedAt: string): MonitoringGap {
  return { id: 1, startedAt, endedAt, missedSchedules: 1 };
}

describe("monitoring gap", () => {
  it("formats durations like the notification", () => {
    expect(formatGapDuration(48_000)).toBe("48초");
    expect(formatGapDuration(59_999)).toBe("59초");
    expect(formatGapDuration(60_000)).toBe("1분");
    expect(formatGapDuration(59 * 60_000 + 59_000)).toBe("59분");
    expect(formatGapDuration(60 * 60_000)).toBe("1시간 0분");
    expect(formatGapDuration(150 * 60_000 + 30_000)).toBe("2시간 30분");
    expect(formatGapDuration(26 * 60 * 60_000)).toBe("26시간 0분");
  });

  it("sums the length of the gaps", () => {
    const gaps = [
      gap("2026-10-02T00:10:00Z", "2026-10-02T02:40:00Z"),
      gap("2026-10-01T15:00:00Z", "2026-10-01T15:20:00Z"),
    ];

    expect(gapDuration(gaps[0])).toBe(150 * 60_000);
    expect(totalGapDuration(gaps)).toBe(170 * 60_000);
    expect(totalGapDuration([])).toBe(0);
  });
});
