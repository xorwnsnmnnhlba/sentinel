import type { Assertion, CaseItem, Headers, HttpMethod } from "@/lib/api/types";

/**
 * 케이스 편집 폼 상태와 API 요청 본문 사이의 변환·검증. 화면(입력 즉시 안내)과 Server Action(재검사)에서 공용.
 * 검증 규칙은 Spring 도메인 검증(RequestSpec, 판정 규칙 record)과 일치. 최종 판단은 Spring
 */

export const HTTP_METHODS: HttpMethod[] = ["GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"];

export const OPERATORS = [
  "EXISTS",
  "NOT_EXISTS",
  "EQUALS",
  "NOT_EQUALS",
  "CONTAINS",
  "MATCHES",
  "GREATER_THAN",
  "GREATER_THAN_OR_EQUAL",
  "LESS_THAN",
  "LESS_THAN_OR_EQUAL",
] as const;

export type Operator = (typeof OPERATORS)[number];

/** header 규칙은 숫자 비교 미지원 */
export const HEADER_OPERATORS: Operator[] = ["EXISTS", "NOT_EXISTS", "EQUALS", "NOT_EQUALS", "CONTAINS", "MATCHES"];

export const OPERATOR_LABELS: Record<Operator, string> = {
  EXISTS: "존재",
  NOT_EXISTS: "없음",
  EQUALS: "같음 (==)",
  NOT_EQUALS: "다름 (!=)",
  CONTAINS: "포함",
  MATCHES: "정규식 일치",
  GREATER_THAN: "초과 (>)",
  GREATER_THAN_OR_EQUAL: "이상 (>=)",
  LESS_THAN: "미만 (<)",
  LESS_THAN_OR_EQUAL: "이하 (<=)",
};

export const ASSERTION_TYPES = ["statusCode", "responseTime", "header", "jsonPath", "bodyContains"] as const;

export type AssertionType = (typeof ASSERTION_TYPES)[number];

export const ASSERTION_LABELS: Record<AssertionType, string> = {
  statusCode: "상태 코드",
  responseTime: "응답 시간",
  header: "응답 헤더",
  jsonPath: "JSON 값 (JsonPath)",
  bodyContains: "본문 포함",
};

/** 입력 중 상태. 숫자·목록도 문자열로 보관하고 저장 시 변환 */
export type AssertionDraft =
  | { type: "statusCode"; anyOf: string }
  | { type: "responseTime"; maxMillis: string }
  | { type: "header"; name: string; operator: Operator; value: string }
  | { type: "jsonPath"; path: string; operator: Operator; expected: string }
  | { type: "bodyContains"; text: string };

export type HeaderRow = { name: string; value: string };

export type CaseDraft = {
  name: string;
  method: HttpMethod;
  path: string;
  headers: HeaderRow[];
  body: string;
  assertions: AssertionDraft[];
};

/** Spring 등록·수정 요청 본문 (suiteId 제외) */
export type CasePayload = {
  name: string;
  request: { method: HttpMethod; path: string; headers: Headers; body: string | null };
  assertions: Assertion[];
};

export type CaseValidation = { ok: true; payload: CasePayload } | { ok: false; errors: string[] };

/** Spring 컬럼 길이와 일치 */
const MAX_NAME = 200;
const MAX_PATH = 2000;
const MAX_HEADER_NAME = 200;
const MAX_BODY = 65_536;
const STATUS_CODE = /^[1-5][0-9x]{2}$/;

export function emptyDraft(): CaseDraft {
  return { name: "", method: "GET", path: "/", headers: [], body: "", assertions: [] };
}

export function newAssertion(type: AssertionType): AssertionDraft {
  switch (type) {
    case "statusCode":
      return { type, anyOf: "2xx" };
    case "responseTime":
      return { type, maxMillis: "2000" };
    case "header":
      return { type, name: "Content-Type", operator: "CONTAINS", value: "json" };
    case "jsonPath":
      return { type, path: "$.", operator: "EXISTS", expected: "" };
    case "bodyContains":
      return { type, text: "" };
  }
}

/** 조회 응답 → 폼 상태. 헤더 값이 여러 개면 행을 나누어 표시 */
export function toDraft(item: CaseItem): CaseDraft {
  return {
    name: item.name,
    method: item.request.method,
    path: item.request.path,
    headers: Object.entries(item.request.headers).flatMap(([name, values]) => values.map((value) => ({ name, value }))),
    body: item.request.body ?? "",
    assertions: item.assertions.map(assertionDraftOf),
  };
}

function assertionDraftOf(assertion: Assertion): AssertionDraft {
  const field = (name: string) => assertion[name];
  switch (assertion.type) {
    case "statusCode":
      return { type: "statusCode", anyOf: ((field("anyOf") as string[] | undefined) ?? []).join(", ") };
    case "responseTime":
      return { type: "responseTime", maxMillis: String(field("maxMillis") ?? "") };
    case "header":
      return {
        type: "header",
        name: String(field("name") ?? ""),
        operator: field("operator") as Operator,
        value: (field("value") as string | null) ?? "",
      };
    case "jsonPath":
      return {
        type: "jsonPath",
        path: String(field("path") ?? ""),
        operator: field("operator") as Operator,
        expected: expectedText(field("expected")),
      };
    case "bodyContains":
      return { type: "bodyContains", text: String(field("text") ?? "") };
    default:
      throw new Error(`unknown assertion type: ${assertion.type}`);
  }
}

/**
 * 기대값 표시. parseExpected로 다시 읽었을 때 같은 값이 되도록 표기 (수정 화면에서 저장해도 값 유지).
 * 숫자·불리언·배열 등은 JSON 표기, 문자열은 그대로 두되 JSON으로 읽히는 문자열("1000", "true")은 따옴표 포함
 */
function expectedText(value: unknown): string {
  if (value === undefined || value === null) {
    return "";
  }
  if (typeof value !== "string") {
    return JSON.stringify(value);
  }
  const reparsed = parseExpected(value);
  return reparsed === value ? value : JSON.stringify(value);
}

/**
 * JsonPath 기대값 해석. JSON 값으로 읽히면 그 값(숫자 1000, true, "1000" 등), 아니면 입력한 문자열 그대로.
 * 문자열 "1000"과 숫자 1000은 Spring에서 불일치이므로 문자열 숫자는 따옴표로 감싸 입력
 */
export function parseExpected(text: string): unknown {
  const trimmed = text.trim();
  if (trimmed === "") {
    return null;
  }
  try {
    return JSON.parse(trimmed);
  } catch {
    return text;
  }
}

export function needsValue(operator: Operator): boolean {
  return operator !== "EXISTS" && operator !== "NOT_EXISTS";
}

function isNumericComparison(operator: Operator): boolean {
  return operator.startsWith("GREATER_THAN") || operator.startsWith("LESS_THAN");
}

/** 폼 상태 검증 후 Spring 요청 본문 생성. 오류는 화면 표시용 문구 목록 */
export function validateDraft(draft: CaseDraft): CaseValidation {
  const errors: string[] = [];
  const name = draft.name.trim();
  if (name === "") {
    errors.push("이름을 입력해 주세요");
  } else if (name.length > MAX_NAME) {
    errors.push(`이름은 ${MAX_NAME}자 이하로 입력해 주세요`);
  }
  if (!HTTP_METHODS.includes(draft.method)) {
    errors.push("지원하지 않는 메서드입니다");
  }
  const path = draft.path.trim();
  // 변수로 시작하는 경로({{prefix}}/items)는 Spring이 실행 시 치환 결과가 /로 시작하는지 확인
  if (!path.startsWith("/") && !path.startsWith("{{")) {
    errors.push("경로는 / 또는 {{변수}}로 시작해야 합니다 (기본 URL 뒤에 붙는 상대 경로)");
  } else if (path.length > MAX_PATH) {
    errors.push(`경로는 ${MAX_PATH}자 이하로 입력해 주세요`);
  }

  const headers: Headers = {};
  draft.headers.forEach((row, index) => {
    const headerName = row.name.trim();
    if (headerName === "" && row.value.trim() === "") {
      return;
    }
    if (headerName === "") {
      errors.push(`요청 헤더 ${index + 1}: 이름을 입력해 주세요`);
    } else if (headerName.length > MAX_HEADER_NAME) {
      errors.push(`요청 헤더 ${index + 1}: 이름은 ${MAX_HEADER_NAME}자 이하로 입력해 주세요`);
    } else {
      (headers[headerName] ??= []).push(row.value);
    }
  });

  if (draft.body.length > MAX_BODY) {
    errors.push(`본문은 ${MAX_BODY}자 이하로 입력해 주세요`);
  }

  const assertions: Assertion[] = [];
  draft.assertions.forEach((assertion, index) => {
    const result = validateAssertion(assertion);
    if (typeof result === "string") {
      errors.push(`판정 규칙 ${index + 1} (${ASSERTION_LABELS[assertion.type]}): ${result}`);
    } else {
      assertions.push(result);
    }
  });

  if (errors.length > 0) {
    return { ok: false, errors };
  }
  return {
    ok: true,
    payload: {
      name,
      request: { method: draft.method, path, headers, body: draft.body === "" ? null : draft.body },
      assertions,
    },
  };
}

/** 규칙 1건 검증. 오류 시 문구 반환 */
function validateAssertion(draft: AssertionDraft): Assertion | string {
  switch (draft.type) {
    case "statusCode": {
      const codes = draft.anyOf
        .split(",")
        .map((code) => code.trim().toLowerCase())
        .filter((code) => code !== "");
      if (codes.length === 0) {
        return "상태 코드를 하나 이상 입력해 주세요 (예: 200, 2xx)";
      }
      const invalid = codes.find((code) => !STATUS_CODE.test(code));
      if (invalid) {
        return `잘못된 상태 코드입니다: ${invalid} (예: 200, 2xx, 40x)`;
      }
      return { type: "statusCode", anyOf: codes };
    }
    case "responseTime": {
      const maxMillis = Number(draft.maxMillis.trim());
      if (draft.maxMillis.trim() === "" || !Number.isSafeInteger(maxMillis) || maxMillis <= 0) {
        return "응답 시간 상한은 1 이상의 정수(ms)로 입력해 주세요";
      }
      return { type: "responseTime", maxMillis };
    }
    case "header": {
      const name = draft.name.trim();
      if (name === "") {
        return "헤더 이름을 입력해 주세요";
      }
      if (!HEADER_OPERATORS.includes(draft.operator)) {
        return "응답 헤더는 숫자 비교를 지원하지 않습니다";
      }
      if (needsValue(draft.operator) && draft.value === "") {
        return "비교할 값을 입력해 주세요";
      }
      return {
        type: "header",
        name,
        operator: draft.operator,
        value: needsValue(draft.operator) ? draft.value : null,
      };
    }
    case "jsonPath": {
      const path = draft.path.trim();
      if (!path.startsWith("$") || path === "$.") {
        return "JsonPath를 $로 시작하여 입력해 주세요 (예: $.items[0].id)";
      }
      if (!OPERATORS.includes(draft.operator)) {
        return "지원하지 않는 비교 방식입니다";
      }
      if (!needsValue(draft.operator)) {
        return { type: "jsonPath", path, operator: draft.operator, expected: null };
      }
      const expected = parseExpected(draft.expected);
      if (expected === null) {
        return "기대값을 입력해 주세요";
      }
      if (isNumericComparison(draft.operator) && typeof expected !== "number") {
        return "크기 비교는 숫자 기대값이 필요합니다";
      }
      if (draft.operator === "MATCHES" && typeof expected !== "string") {
        return "정규식은 문자열로 입력해 주세요";
      }
      return { type: "jsonPath", path, operator: draft.operator, expected };
    }
    case "bodyContains":
      if (draft.text === "") {
        return "포함할 문자열을 입력해 주세요";
      }
      return { type: "bodyContains", text: draft.text };
  }
}

/** Server Action 입력 형태 확인. 화면 없이 직접 호출된 요청도 validateDraft 전에 구조부터 검사 */
export function isCaseDraft(value: unknown): value is CaseDraft {
  if (typeof value !== "object" || value === null) {
    return false;
  }
  const draft = value as Record<string, unknown>;
  const isString = (field: unknown) => typeof field === "string";
  return (
    isString(draft.name) &&
    isString(draft.method) &&
    isString(draft.path) &&
    isString(draft.body) &&
    Array.isArray(draft.headers) &&
    draft.headers.every(
      (row) => typeof row === "object" && row !== null && isString(row.name) && isString(row.value),
    ) &&
    Array.isArray(draft.assertions) &&
    draft.assertions.every(isAssertionDraft)
  );
}

function isAssertionDraft(value: unknown): value is AssertionDraft {
  if (typeof value !== "object" || value === null) {
    return false;
  }
  const draft = value as Record<string, unknown>;
  const strings = (...fields: string[]) => fields.every((field) => typeof draft[field] === "string");
  switch (draft.type) {
    case "statusCode":
      return strings("anyOf");
    case "responseTime":
      return strings("maxMillis");
    case "header":
      return strings("name", "operator", "value");
    case "jsonPath":
      return strings("path", "operator", "expected");
    case "bodyContains":
      return strings("text");
    default:
      return false;
  }
}
