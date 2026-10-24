import { describe, expect, it } from "vitest";

import { describeInputError, INPUT_ERROR_TEXTS } from "./input-error";

describe("input error", () => {
  it("covers every coded input error", () => {
    // Spring InvalidValueException.Code와 같은 목록
    expect(Object.keys(INPUT_ERROR_TEXTS)).toEqual([
      "INVALID_JSON_PATH",
      "INVALID_REGEX",
      "CRON_FIELD_COUNT",
      "CRON_INVALID_FIELD",
      "CRON_INVALID",
      "CRON_NEVER_FIRES",
    ]);
  });

  it("names the cron field and what it accepts", () => {
    expect(describeInputError("CRON_INVALID_FIELD", { field: "weekday", value: "MON-FRY" })).toBe(
      "cron의 요일(0~7, SUN~SAT. 0과 7은 일요일) 칸이 올바르지 않습니다: MON-FRY",
    );
    expect(describeInputError("CRON_INVALID_FIELD", { field: "second", value: "1" })).toBe(
      "cron의 second 칸이 올바르지 않습니다: 1",
    );
    expect(describeInputError("CRON_NEVER_FIRES", { cron: "0 0 30 2 *" })).toBe(
      "실행 시각이 오지 않는 cron입니다 (예: 2월 30일): 0 0 30 2 *",
    );
  });

  it("says which rule or extraction has the error", () => {
    expect(describeInputError("INVALID_REGEX", { expression: "(", list: "assertions", index: "0" })).toBe(
      "판정 규칙 1: 정규식 문법이 올바르지 않습니다: (",
    );
    expect(describeInputError("INVALID_JSON_PATH", { expression: "$.[", list: "extractors", index: "2" })).toBe(
      "값 추출 3: JsonPath 문법이 올바르지 않습니다: $.[",
    );
    // 위치를 모르면 문장만
    const unknownPlaces: Record<string, string>[] = [
      {},
      { list: "headers", index: "0" },
      { list: "toString", index: "0" },
      { list: "assertions" },
      { list: "assertions", index: "" },
    ];
    for (const params of unknownPlaces) {
      expect(describeInputError("INVALID_REGEX", { expression: "(", ...params })).toBe(
        "정규식 문법이 올바르지 않습니다: (",
      );
    }
  });

  it("gives nothing without a code, with an unknown code or a missing value", () => {
    expect(describeInputError(undefined, { expression: "(" })).toBeNull();
    expect(describeInputError("NEW_ERROR", {})).toBeNull();
    expect(describeInputError("INVALID_REGEX", {})).toBeNull();
  });
});
