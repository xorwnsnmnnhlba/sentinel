import { describe, expect, it } from "vitest";

import type { EnvironmentItem, ProjectItem, ScheduleDetail, SuiteItem } from "@/lib/api/types";

import { filterRows, parseStatus, scheduleRows } from "./schedule-overview";

const projects: ProjectItem[] = [
  { id: 1, name: "결제", description: null, webhookConfigured: false },
  { id: 2, name: "가입", description: null, webhookConfigured: false },
];
const suites: SuiteItem[] = [
  { id: 10, projectId: 1, name: "승인", description: null, caseCount: 3 },
  { id: 20, projectId: 2, name: "인증", description: null, caseCount: 1 },
];
const environments: EnvironmentItem[] = [
  { id: 100, projectId: 1, name: "dev", baseUrl: "http://dev", variables: {}, secretNames: [] },
];

function schedule(id: number, suiteId: number, enabled: boolean, nextRunAt: string | null): ScheduleDetail {
  return {
    id,
    suiteId,
    environmentId: 100,
    cron: "10 9 * * 1-5",
    zone: "Asia/Seoul",
    enabled,
    skipHolidays: false,
    nextRunAt,
    lastTriggeredAt: null,
  };
}

describe("scheduleRows", () => {
  it("names the project, suite and environment and orders by the next run, disabled ones last", () => {
    const rows = scheduleRows(
      [
        schedule(1, 10, true, "2026-10-08T03:00:00Z"),
        schedule(2, 10, false, null),
        schedule(3, 20, true, "2026-10-08T00:10:00Z"),
        schedule(4, 20, false, null),
        schedule(5, 99, true, "2026-10-08T00:00:00Z"),
        { ...schedule(6, 20, true, "2026-10-08T03:00:00Z"), environmentId: 999 },
      ],
      projects,
      suites,
      environments,
    );

    // 스위트를 찾을 수 없는 스케줄(5)은 제외. 같은 시각은 ID 순, 꺼진 스케줄은 프로젝트 이름 순(가입 < 결제)
    expect(rows.map((row) => row.schedule.id)).toEqual([3, 1, 6, 4, 2]);
    expect(rows[0]).toMatchObject({ project: { id: 2, name: "가입" }, suite: { id: 20, name: "인증" } });
    expect(rows[1].environmentName).toBe("dev");
    expect(rows[2].environmentName).toBe("-");
  });
});

describe("filterRows", () => {
  const rows = scheduleRows(
    [
      schedule(1, 10, true, "2026-10-08T03:00:00Z"),
      schedule(2, 10, false, null),
      schedule(3, 20, true, "2026-10-08T00:10:00Z"),
    ],
    projects,
    suites,
    environments,
  );

  it("narrows by project and status", () => {
    expect(filterRows(rows, {}).map((row) => row.schedule.id)).toEqual([3, 1, 2]);
    expect(filterRows(rows, { projectId: 1 }).map((row) => row.schedule.id)).toEqual([1, 2]);
    expect(filterRows(rows, { status: "enabled" }).map((row) => row.schedule.id)).toEqual([3, 1]);
    expect(filterRows(rows, { projectId: 1, status: "disabled" }).map((row) => row.schedule.id)).toEqual([2]);
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
