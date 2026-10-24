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
      "REQUIRED",
      "TOO_LONG",
      "BASE_URL_INVALID",
      "BASE_URL_SCHEME",
      "BASE_URL_HOST",
      "BASE_URL_QUERY",
      "USED_AS_SECRET",
      "USED_AS_VARIABLE",
      "EMAIL_INVALID",
      "SECRETS_DISABLED",
      "SECRETS_KEY_MISSING",
      "SECRETS_UNREADABLE",
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

  it("names the item with a blank or too long value", () => {
    expect(describeInputError("REQUIRED", { target: "baseUrl" })).toBe("기본 URL: 값을 입력해 주세요");
    expect(describeInputError("TOO_LONG", { target: "secret name", max: "100" })).toBe(
      "시크릿 이름: 100자 이하로 입력해 주세요",
    );
    expect(describeInputError("REQUIRED", { target: "inviteToken" })).toBe("inviteToken: 값을 입력해 주세요");
  });

  it("says what is wrong with the environment", () => {
    expect(describeInputError("BASE_URL_SCHEME", { value: "ftp://gw" })).toBe(
      "기본 URL은 http:// 또는 https://로 시작해야 합니다: ftp://gw",
    );
    expect(describeInputError("USED_AS_VARIABLE", { name: "token" })).toBe(
      "시크릿 token: 같은 이름의 변수가 있습니다. 이름을 바꾸거나 한쪽을 지워 주세요",
    );
    expect(describeInputError("SECRETS_UNREADABLE", {})).toBe(
      "이 환경의 시크릿을 읽을 수 없습니다. 서버 암호화 키가 바뀌었다면 환경 수정에서 시크릿 값을 다시 입력해 주세요",
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
