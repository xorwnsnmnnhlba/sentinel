import { describe, expect, it } from "vitest";

import { holidayError, kstToday, parseHolidayLines, upcomingLabel, weekday, yearOf } from "./holiday";

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

describe("parseHolidayLines", () => {
  it("reads one holiday per line in the formats calendars use", () => {
    expect(
      parseHolidayLines(
        [
          "2026-12-25 성탄절",
          "",
          "2027.1.1\t신정",
          "  2027/02/06, 설날 연휴  ",
          "2027. 2. 7. 설날",
          "2027-02-08 (월) 대체공휴일",
          "2027-03-01(월) 삼일절",
        ].join("\r\n"),
      ),
    ).toEqual({
      holidays: [
        { date: "2026-12-25", name: "성탄절" },
        { date: "2027-01-01", name: "신정" },
        { date: "2027-02-06", name: "설날 연휴" },
        { date: "2027-02-07", name: "설날" },
        { date: "2027-02-08", name: "대체공휴일" },
        { date: "2027-03-01", name: "삼일절" },
      ],
    });
  });

  it.each([
    ["성탄절 2026-12-25", "1번째 줄: 날짜를 읽을 수 없습니다 (예: 2026-12-25 성탄절)"],
    ["12-25 성탄절", "1번째 줄: 날짜를 읽을 수 없습니다 (예: 2026-12-25 성탄절)"],
    ["2026-12-25 성탄절\n\n2026-02-30 없는 날", "3번째 줄: 2026-02-30은(는) 없는 날짜입니다"],
    ["2026-12-25", "1번째 줄: 이름을 입력해 주세요"],
    ["2026-12-25 (금)", "1번째 줄: 이름을 입력해 주세요"],
    [`2026-12-25 ${"가".repeat(101)}`, "1번째 줄: 이름은 100자 이하로 입력해 주세요"],
    ["2026-12-25 성탄절\n2026.12.25 크리스마스", "2번째 줄: 2026-12-25이(가) 앞 줄에 이미 있습니다"],
    ["", "붙여 넣은 휴일이 없습니다"],
    [" \n\t\n", "붙여 넣은 휴일이 없습니다"],
  ])("explains the first problem in %j", (text, error) => {
    expect(parseHolidayLines(text)).toEqual({ error });
  });

  it("accepts up to 100 holidays at once", () => {
    const lines = (count: number) =>
      Array.from({ length: count }, (_, i) => {
        const date = new Date(Date.UTC(2026, 0, 1 + i)).toISOString().slice(0, 10);
        return `${date} 휴일 ${i}`;
      }).join("\n");

    expect(parseHolidayLines(lines(100))).toMatchObject({ holidays: expect.any(Array) });
    expect(parseHolidayLines(lines(101))).toEqual({ error: "한 번에 100건까지 등록할 수 있습니다 (지금 101건)" });
  });
});
