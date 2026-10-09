import { describe, expect, it } from "vitest";

import type { ScheduleDetail } from "@/lib/api/types";

import { filterSchedules, parseStatus, sortSchedules } from "./schedule-overview";

const payment = { id: 1, name: "결제" };
const signUp = { id: 2, name: "가입" };

function schedule(
  id: number,
  project: { id: number; name: string },
  suite: string,
  enabled: boolean,
  nextRunAt: string | null,
): ScheduleDetail {
  return {
    id,
    project,
    suite: { id: id * 10, name: suite },
    environment: { id: 100, name: "dev" },
    cron: "10 9 * * 1-5",
    zone: "Asia/Seoul",
    enabled,
    skipHolidays: false,
    nextRunAt,
    lastTriggeredAt: null,
  };
}

describe("sortSchedules", () => {
  it("orders by the next run, disabled ones last by project and suite name", () => {
    const sorted = sortSchedules([
      schedule(1, payment, "승인", true, "2026-10-08T03:00:00Z"),
      schedule(2, payment, "승인", false, null),
      schedule(3, signUp, "인증", true, "2026-10-08T00:10:00Z"),
      schedule(4, signUp, "인증", false, null),
      schedule(6, signUp, "인증", true, "2026-10-08T03:00:00Z"),
    ]);

    // 같은 시각은 ID 순, 꺼진 스케줄은 프로젝트 이름 순(가입 < 결제)
    expect(sorted.map((item) => item.id)).toEqual([3, 1, 6, 4, 2]);
  });
});

describe("filterSchedules", () => {
  const schedules = sortSchedules([
    schedule(1, payment, "승인", true, "2026-10-08T03:00:00Z"),
    schedule(2, payment, "승인", false, null),
    schedule(3, signUp, "인증", true, "2026-10-08T00:10:00Z"),
  ]);

  it("narrows by project and status", () => {
    expect(filterSchedules(schedules, {}).map((item) => item.id)).toEqual([3, 1, 2]);
    expect(filterSchedules(schedules, { projectId: 1 }).map((item) => item.id)).toEqual([1, 2]);
    expect(filterSchedules(schedules, { status: "enabled" }).map((item) => item.id)).toEqual([3, 1]);
    expect(filterSchedules(schedules, { projectId: 1, status: "disabled" }).map((item) => item.id)).toEqual([2]);
  });
});

describe("parseStatus", () => {
  it("accepts only known values", () => {
    expect(parseStatus("enabled")).toBe("enabled");
    expect(parseStatus("disabled")).toBe("disabled");
    expect(parseStatus("all")).toBeUndefined();
    expect(parseStatus(undefined)).toBeUndefined();
  });
});
