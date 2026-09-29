import { describe, expect, it } from "vitest";

import type { MaintenanceWindow } from "@/lib/api/types";

import { formatEnd, formatPeriod, fromKstInput, maintenanceOf, periodError, toKstInput } from "./maintenance";

function window(patch: Partial<MaintenanceWindow>): MaintenanceWindow {
  return {
    id: 1,
    environment: { id: 1, name: "dev" },
    projectId: 1,
    startsAt: "2026-09-29T01:00:00Z",
    endsAt: "2026-09-29T02:00:00Z",
    reason: null,
    active: false,
    createdBy: null,
    ...patch,
  };
}

describe("KST input", () => {
  it("shows an instant as KST date and minutes", () => {
    expect(toKstInput(new Date("2026-09-29T01:05:59Z"))).toBe("2026-09-29T10:05");
    expect(toKstInput(new Date("2026-09-29T15:30:00Z"))).toBe("2026-09-30T00:30");
  });

  it("reads KST date and minutes as an instant", () => {
    expect(fromKstInput("2026-09-29T10:05")).toBe("2026-09-29T01:05:00.000Z");
    expect(fromKstInput("2026-09-30T00:30")).toBe("2026-09-29T15:30:00.000Z");
  });

  it.each(["", "2026-09-29", "2026-09-29 10:05", "2026-02-30T10:00", "2026-09-29T24:00"])("rejects %j", (value) => {
    expect(fromKstInput(value)).toBeNull();
  });
});

describe("periodError", () => {
  const now = new Date("2026-09-29T01:00:00Z");

  it("accepts a future end up to seven days after the start", () => {
    expect(periodError("2026-09-29T00:00:00Z", "2026-10-06T00:00:00Z", now)).toBeNull();
  });

  it.each([
    ["2026-09-29T02:00:00Z", "2026-09-29T02:00:00Z", "끝 시각은 시작 시각보다 뒤여야 합니다"],
    ["2026-09-29T00:00:00Z", "2026-09-29T01:00:00Z", "끝 시각이 이미 지났습니다"],
    ["2026-09-29T02:00:00Z", "2026-10-06T02:00:01Z", "점검 시간은 최대 7일입니다"],
  ])("explains %s ~ %s", (startsAt, endsAt, message) => {
    expect(periodError(startsAt, endsAt, now)).toBe(message);
  });
});

describe("formatPeriod", () => {
  it("shows only the end time within a day", () => {
    expect(formatPeriod("2026-09-29T01:00:00Z", "2026-09-29T02:30:00Z")).toBe("09-29 10:00 ~ 11:30");
  });

  it("shows the end date across days (KST)", () => {
    expect(formatPeriod("2026-09-29T13:00:00Z", "2026-09-29T17:00:00Z")).toBe("09-29 22:00 ~ 09-30 02:00");
  });
});

describe("formatEnd", () => {
  it("shows the date only when the end is not today (KST)", () => {
    const now = new Date("2026-09-29T01:00:00Z");
    expect(formatEnd("2026-09-29T02:00:00Z", now)).toBe("11:00");
    expect(formatEnd("2026-09-29T17:00:00Z", now)).toBe("09-30 02:00");
  });
});

describe("maintenanceOf", () => {
  it("prefers the active window, then the first planned one of the environment", () => {
    const planned = window({ id: 1 });
    const later = window({ id: 2, startsAt: "2026-09-30T01:00:00Z" });
    const active = window({ id: 3, active: true });
    const other = window({ id: 4, environment: { id: 2, name: "staging" }, active: true });

    expect(maintenanceOf([planned, later, active, other], 1)).toBe(active);
    expect(maintenanceOf([planned, later, other], 1)).toBe(planned);
    expect(maintenanceOf([other], 1)).toBeUndefined();
  });
});
