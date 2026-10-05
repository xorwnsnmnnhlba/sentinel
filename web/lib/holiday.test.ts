import { describe, expect, it } from "vitest";

import { holidayError, kstToday, upcomingLabel, weekday, yearOf } from "./holiday";

describe("kstToday", () => {
  it("uses the Korean date even when UTC is still the day before", () => {
    expect(kstToday(new Date("2026-12-31T15:00:00Z"))).toBe("2027-01-01");
    expect(kstToday(new Date("2026-12-31T14:59:59Z"))).toBe("2026-12-31");
  });
});

describe("yearOf", () => {
  it.each([
    ["2027", 2027],
    ["2000", 2000],
    ["2100", 2100],
    [undefined, 2026],
    ["", 2026],
    ["1999", 2026],
    ["2101", 2026],
    ["2026.5", 2026],
    ["abc", 2026],
  ])("reads %j as %i", (param, year) => {
    expect(yearOf(param, "2026-10-05")).toBe(year);
  });
});

describe("weekday", () => {
  it.each([
    ["2026-10-04", "일"],
    ["2026-10-05", "월"],
    ["2026-10-09", "금"],
    ["2026-10-10", "토"],
  ])("%s is %s", (date, day) => {
    expect(weekday(date)).toBe(day);
  });
});

describe("holidayError", () => {
  it("accepts a real date and a name of up to 100 characters", () => {
    expect(holidayError("2026-10-09", "한글날")).toBeNull();
    expect(holidayError("2028-02-29", "가".repeat(100))).toBeNull();
  });

  it.each([
    ["", "한글날", "날짜를 입력해 주세요"],
    ["2026-02-30", "한글날", "날짜를 입력해 주세요"],
    ["2026-13-01", "한글날", "날짜를 입력해 주세요"],
    ["2026-1-9", "한글날", "날짜를 입력해 주세요"],
    ["2026-10-09", "  ", "이름을 입력해 주세요"],
    ["2026-10-09", "가".repeat(101), "이름은 100자 이하로 입력해 주세요"],
  ])("rejects %j %j", (date, name, message) => {
    expect(holidayError(date, name)).toBe(message);
  });
});

describe("upcomingLabel", () => {
  const holidays = [
    { date: "2026-10-03", name: "개천절" },
    { date: "2026-10-09", name: "한글날" },
  ];

  it("names today's holiday or the nearest one after today", () => {
    expect(upcomingLabel(holidays, "2026-10-03")).toBe("오늘 개천절");
    expect(upcomingLabel(holidays, "2026-10-04")).toBe("다음 10-09 한글날");
    expect(upcomingLabel(holidays, "2026-10-10")).toBeUndefined();
  });
});
