import { describe, expect, it } from "vitest";

import type { CaseItem } from "@/lib/api/types";

import {
  type AssertionDraft,
  type CaseDraft,
  emptyDraft,
  isCaseDraft,
  newAssertion,
  parseExpected,
  toDraft,
  validateDraft,
} from "./case-draft";

function draft(patch: Partial<CaseDraft> = {}): CaseDraft {
  return { ...emptyDraft(), name: "health", path: "/health", ...patch };
}

function withAssertion(assertion: AssertionDraft) {
  return validateDraft(draft({ assertions: [assertion] }));
}

function errorsOf(result: ReturnType<typeof validateDraft>): string[] {
  return result.ok ? [] : result.errors;
}

describe("toDraft", () => {
  it("turns a saved case into form state and back into the same request", () => {
    const item: CaseItem = {
      id: 1,
      suiteId: 2,
      name: "결제 요청",
      request: {
        method: "POST",
        path: "/billing/v1/charge",
        headers: { Accept: ["application/json", "text/plain"], "Content-Type": ["application/json"] },
        body: '{"amount": 1000}',
      },
      assertions: [
        { type: "statusCode", anyOf: ["200", "2xx"] },
        { type: "responseTime", maxMillis: 2000 },
        { type: "header", name: "Content-Type", operator: "CONTAINS", value: "json" },
        { type: "jsonPath", path: "$.amount", operator: "EQUALS", expected: 1000 },
        { type: "jsonPath", path: "$.id", operator: "EQUALS", expected: "1000" },
        { type: "jsonPath", path: "$.items", operator: "EXISTS", expected: null },
        { type: "bodyContains", text: "OK" },
      ],
    };

    const form = toDraft(item);

    expect(form.headers).toEqual([
      { name: "Accept", value: "application/json" },
      { name: "Accept", value: "text/plain" },
      { name: "Content-Type", value: "application/json" },
    ]);
    expect(form.assertions[0]).toEqual({ type: "statusCode", anyOf: "200, 2xx" });
    expect(form.assertions[3]).toMatchObject({ expected: "1000" });
    // 문자열 "1000"은 따옴표를 포함해 표시하여 저장 시 숫자로 바뀌지 않음
    expect(form.assertions[4]).toMatchObject({ expected: '"1000"' });
    const result = validateDraft(form);
    expect(result.ok && result.payload).toEqual({
      name: item.name,
      request: item.request,
      assertions: item.assertions,
    });
  });

  it("keeps an empty body as empty text", () => {
    const form = toDraft({
      id: 1,
      suiteId: 1,
      name: "a",
      request: { method: "GET", path: "/", headers: {}, body: null },
      assertions: [],
    });

    expect(form.body).toBe("");
    expect(validateDraft(form)).toMatchObject({ ok: true, payload: { request: { body: null } } });
  });
});

describe("parseExpected", () => {
  it.each([
    ["1000", 1000],
    ["1.5", 1.5],
    ["true", true],
    ['"1000"', "1000"],
    ["UP", "UP"],
    ["  ", null],
    ["[1, 2]", [1, 2]],
  ])("%j → %j", (text, value) => {
    expect(parseExpected(text)).toEqual(value);
  });
});

describe("validateDraft request", () => {
  it("builds the Spring payload with trimmed name and path", () => {
    const result = validateDraft(
      draft({
        name: "  health  ",
        method: "POST",
        path: " /health ",
        headers: [
          { name: " Accept ", value: "application/json" },
          { name: "", value: "" },
        ],
        body: "{}",
      }),
    );

    expect(result).toEqual({
      ok: true,
      payload: {
        name: "health",
        request: { method: "POST", path: "/health", headers: { Accept: ["application/json"] }, body: "{}" },
        assertions: [],
      },
    });
  });

  it("collects every problem at once", () => {
    const errors = errorsOf(
      validateDraft(
        draft({ name: " ", path: "health", headers: [{ name: "", value: "x" }], body: "a".repeat(65_537) }),
      ),
    );

    expect(errors).toEqual([
      "이름을 입력해 주세요",
      "경로는 / 또는 {{변수}}로 시작해야 합니다 (기본 URL 뒤에 붙는 상대 경로)",
      "요청 헤더 1: 이름을 입력해 주세요",
      "본문은 65536자 이하로 입력해 주세요",
    ]);
  });

  it("accepts a path starting with a variable", () => {
    expect(validateDraft(draft({ path: "{{apiPrefix}}/runs" }))).toMatchObject({
      ok: true,
      payload: { request: { path: "{{apiPrefix}}/runs" } },
    });
    expect(errorsOf(validateDraft(draft({ path: "{apiPrefix}/runs" })))).toEqual([
      "경로는 / 또는 {{변수}}로 시작해야 합니다 (기본 URL 뒤에 붙는 상대 경로)",
    ]);
  });

  it("enforces Spring column lengths", () => {
    expect(errorsOf(validateDraft(draft({ name: "a".repeat(201) })))).toEqual(["이름은 200자 이하로 입력해 주세요"]);
    expect(errorsOf(validateDraft(draft({ path: "/" + "a".repeat(2000) })))).toEqual([
      "경로는 2000자 이하로 입력해 주세요",
    ]);
    expect(validateDraft(draft({ name: "a".repeat(200), body: "a".repeat(65_536) })).ok).toBe(true);
  });
});

describe("validateDraft assertions", () => {
  it("accepts every default rule except the ones that need input", () => {
    expect(withAssertion(newAssertion("statusCode")).ok).toBe(true);
    expect(withAssertion(newAssertion("responseTime")).ok).toBe(true);
    expect(withAssertion(newAssertion("header")).ok).toBe(true);
    expect(withAssertion(newAssertion("jsonPath")).ok).toBe(false);
    expect(withAssertion(newAssertion("bodyContains")).ok).toBe(false);
  });

  it("normalizes status codes", () => {
    expect(withAssertion({ type: "statusCode", anyOf: " 200, 2XX,,40x " })).toMatchObject({
      payload: { assertions: [{ type: "statusCode", anyOf: ["200", "2xx", "40x"] }] },
    });
  });

  it.each([
    [{ type: "statusCode", anyOf: " , " }, "상태 코드를 하나 이상 입력해 주세요 (예: 200, 2xx)"],
    [{ type: "statusCode", anyOf: "200, 600" }, "잘못된 상태 코드입니다: 600 (예: 200, 2xx, 40x)"],
    [{ type: "responseTime", maxMillis: "0" }, "응답 시간 상한은 1 이상의 정수(ms)로 입력해 주세요"],
    [{ type: "responseTime", maxMillis: "1.5" }, "응답 시간 상한은 1 이상의 정수(ms)로 입력해 주세요"],
    [{ type: "responseTime", maxMillis: "" }, "응답 시간 상한은 1 이상의 정수(ms)로 입력해 주세요"],
    [{ type: "header", name: " ", operator: "EXISTS", value: "" }, "헤더 이름을 입력해 주세요"],
    [{ type: "header", name: "X-A", operator: "EQUALS", value: "" }, "비교할 값을 입력해 주세요"],
    [
      { type: "header", name: "X-A", operator: "GREATER_THAN", value: "1" },
      "응답 헤더는 숫자 비교를 지원하지 않습니다",
    ],
    [
      { type: "jsonPath", path: "items", operator: "EXISTS", expected: "" },
      "JsonPath를 $로 시작하여 입력해 주세요 (예: $.items[0].id)",
    ],
    [{ type: "jsonPath", path: "$.a", operator: "EQUALS", expected: " " }, "기대값을 입력해 주세요"],
    [
      { type: "jsonPath", path: "$.a", operator: "GREATER_THAN", expected: '"10"' },
      "크기 비교는 숫자 기대값이 필요합니다",
    ],
    [{ type: "jsonPath", path: "$.a", operator: "MATCHES", expected: "1" }, "정규식은 문자열로 입력해 주세요"],
    [{ type: "bodyContains", text: "" }, "포함할 문자열을 입력해 주세요"],
  ] as [AssertionDraft, string][])("rejects %j", (assertion, message) => {
    const label = {
      statusCode: "상태 코드",
      responseTime: "응답 시간",
      header: "응답 헤더",
      jsonPath: "JSON 값 (JsonPath)",
      bodyContains: "본문 포함",
    }[assertion.type];

    expect(errorsOf(withAssertion(assertion))).toEqual([`판정 규칙 1 (${label}): ${message}`]);
  });

  it("drops values that the operator does not use", () => {
    expect(withAssertion({ type: "header", name: "X-A", operator: "NOT_EXISTS", value: "leftover" })).toMatchObject({
      payload: { assertions: [{ type: "header", name: "X-A", operator: "NOT_EXISTS", value: null }] },
    });
    expect(withAssertion({ type: "jsonPath", path: "$.a", operator: "EXISTS", expected: "leftover" })).toMatchObject({
      payload: { assertions: [{ type: "jsonPath", path: "$.a", operator: "EXISTS", expected: null }] },
    });
  });

  it("parses JsonPath expected values as JSON", () => {
    expect(
      withAssertion({ type: "jsonPath", path: "$.n", operator: "GREATER_THAN_OR_EQUAL", expected: "1000" }),
    ).toMatchObject({ payload: { assertions: [{ expected: 1000 }] } });
    expect(withAssertion({ type: "jsonPath", path: "$.s", operator: "MATCHES", expected: "^UP$" })).toMatchObject({
      payload: { assertions: [{ expected: "^UP$" }] },
    });
  });

  it("numbers errors by rule position", () => {
    const errors = errorsOf(
      validateDraft(
        draft({ assertions: [newAssertion("statusCode"), { type: "bodyContains", text: "" }, newAssertion("header")] }),
      ),
    );

    expect(errors).toEqual(["판정 규칙 2 (본문 포함): 포함할 문자열을 입력해 주세요"]);
  });
});

describe("isCaseDraft", () => {
  it("accepts a well-formed draft", () => {
    expect(isCaseDraft(draft({ headers: [{ name: "A", value: "b" }], assertions: [newAssertion("jsonPath")] }))).toBe(
      true,
    );
  });

  it.each([
    null,
    "draft",
    { ...emptyDraft(), name: 1 },
    { ...emptyDraft(), headers: "Accept: json" },
    { ...emptyDraft(), headers: [{ name: "A" }] },
    { ...emptyDraft(), assertions: [{ type: "unknown" }] },
    { ...emptyDraft(), assertions: [{ type: "responseTime", maxMillis: 100 }] },
  ])("rejects %j", (value) => {
    expect(isCaseDraft(value)).toBe(false);
  });
});

describe("expected value round trip", () => {
  it.each([1000, "1000", "true", true, "UP", " spaced ", "[1]", [1, "a"], { a: 1 }, "null"])(
    "keeps %j unchanged after editing",
    (expected) => {
      const form = toDraft({
        id: 1,
        suiteId: 1,
        name: "a",
        request: { method: "GET", path: "/", headers: {}, body: null },
        assertions: [{ type: "jsonPath", path: "$.a", operator: "EQUALS", expected }],
      });

      expect(validateDraft(form)).toMatchObject({ ok: true, payload: { assertions: [{ expected }] } });
    },
  );
});
