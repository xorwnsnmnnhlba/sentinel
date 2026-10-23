import { describe, expect, it } from "vitest";

import { describeImportError, describeImportWarning, IMPORT_ERROR_TEXTS, IMPORT_WARNING_TEXTS } from "./import-message";

describe("import warning", () => {
  it("covers every warning the converters send", () => {
    // Spring ImportWarning.Code와 같은 목록
    expect(Object.keys(IMPORT_WARNING_TEXTS)).toEqual([
      "VARIABLE_RENAMED",
      "METHOD_SKIPPED",
      "DEPRECATED",
      "COOKIE_PARAMETER",
      "SECURITY_SCHEME_UNDEFINED",
      "SECURITY_SCHEME_UNSUPPORTED",
      "SECURITY_SCHEME_SECRET",
      "SECURITY_SCHEME_TOKEN",
      "BODY_MEDIA_TYPE_UNSUPPORTED",
      "BODY_MISSING",
      "BODY_FROM_SCHEMA",
      "BODY_TOO_LONG",
      "EXTERNAL_EXAMPLE",
      "NO_SUCCESS_RESPONSE",
      "REFERENCE_UNRESOLVED",
      "REFERENCE_EXTERNAL",
      "REFERENCE_NOT_FOUND",
      "GRAPHQL_VARIABLES_INVALID",
      "BODY_MODE_UNSUPPORTED",
      "BASIC_AUTH_SECRET",
      "API_KEY_NAME_MISSING",
      "AUTH_UNSUPPORTED",
      "CREDENTIAL_REPLACED",
      "CREDENTIAL_SECRET",
      "CREDENTIAL_TOKEN",
      "SCRIPT_UNSUPPORTED",
      "COLLECTION_VARIABLES",
    ]);
  });

  it("fills in the values the server sent", () => {
    expect(
      describeImportWarning({
        code: "SECURITY_SCHEME_TOKEN",
        params: { scheme: "token", sentAs: "'Authorization: Bearer {{token}}'", variable: "{{token}}" },
        message: "security scheme 'token' is sent as ...",
      }),
    ).toBe(
      "보안 스킴 'token' → 'Authorization: Bearer {{token}}'. 환경에 시크릿 {{token}} 등록이 필요합니다. 로그인 요청이 발급하는 토큰이면 앞 케이스의 값 추출로 {{token}}에 받아도 됩니다",
    );
    expect(describeImportWarning({ code: "DEPRECATED", params: {}, message: "operation is deprecated" })).toBe(
      "사용 중단(deprecated)으로 표시된 작업입니다",
    );
  });

  it("names locations, credentials and scripts in Korean and keeps the rest as sent", () => {
    const text = (code: string, params: Record<string, string>) => describeImportWarning({ code, params, message: "" });

    expect(text("REFERENCE_NOT_FOUND", { location: "request body", ref: "#/gone" })).toBe(
      "요청 본문: 참조 대상을 문서에서 찾지 못해 생략했습니다 ('#/gone')",
    );
    expect(text("REFERENCE_NOT_FOUND", { location: "/missing", ref: "#/gone" })).toBe(
      "/missing: 참조 대상을 문서에서 찾지 못해 생략했습니다 ('#/gone')",
    );
    expect(text("CREDENTIAL_TOKEN", { credential: "bearerToken", variable: "{{bearerToken}}" })).toMatch(
      /^환경에 시크릿 \{\{bearerToken\}\} 등록이 필요합니다 \(Bearer 토큰\)\. /,
    );
    expect(text("CREDENTIAL_REPLACED", { credential: "header", header: "Cookie", variable: "{{cookie}}" })).toBe(
      "평문 'Cookie' 헤더 값 → 변수 {{cookie}} (케이스에 자격증명을 남기지 않음)",
    );
    expect(text("CREDENTIAL_SECRET", { credential: "clientSecret", variable: "{{x}}" })).toBe(
      "환경에 시크릿 {{x}} 등록이 필요합니다 (clientSecret)",
    );
    expect(text("SCRIPT_UNSUPPORTED", { script: "prerequest" })).toBe(
      "사전 요청(pre-request) 스크립트: 지원하지 않습니다. 판정 규칙으로 대신해 주세요",
    );
    expect(text("SCRIPT_UNSUPPORTED", { script: "toString" })).toBe(
      "toString 스크립트: 지원하지 않습니다. 판정 규칙으로 대신해 주세요",
    );
    expect(text("BODY_TOO_LONG", { limit: "1048576" })).toBe("요청 본문이 1,048,576자를 넘어 비워 두었습니다");
    expect(text("BODY_TOO_LONG", { limit: "many" })).toBe("요청 본문이 many자를 넘어 비워 두었습니다");
  });

  it("shows the server's message for an unknown warning or a missing value", () => {
    expect(describeImportWarning({ code: "NEW_WARNING", params: {}, message: "something new" })).toBe("something new");
    expect(describeImportWarning({ code: "toString", params: {}, message: "inherited name" })).toBe("inherited name");
    expect(describeImportWarning({ code: "COOKIE_PARAMETER", params: {}, message: "no name" })).toBe("no name");
    expect(
      describeImportWarning({
        code: "CREDENTIAL_SECRET",
        params: { credential: "header", variable: "{{cookie}}" },
        message: "no header name",
      }),
    ).toBe("no header name");
  });
});

describe("import error", () => {
  it("covers every document the converters reject", () => {
    // Spring ImportDocumentException.Code와 같은 목록
    expect(Object.keys(IMPORT_ERROR_TEXTS)).toEqual([
      "EMPTY",
      "TOO_LONG",
      "INVALID_SYNTAX",
      "UNREADABLE",
      "NOT_AN_OBJECT",
      "SWAGGER_2",
      "NOT_OPENAPI",
      "OPENAPI_VERSION",
      "NO_PATHS",
      "NO_OPERATIONS",
      "POSTMAN_ENVIRONMENT",
      "POSTMAN_V1",
      "NOT_POSTMAN",
      "POSTMAN_SCHEMA",
      "NO_REQUESTS",
    ]);
  });

  it("says what to do with the document in Korean", () => {
    expect(describeImportError("NOT_OPENAPI", {}, "found openapi none")).toBe(
      "openapi 항목이 없어 OpenAPI 문서가 아닙니다. Postman 컬렉션이면 형식을 Postman으로 골라 주세요",
    );
    expect(describeImportError("TOO_LONG", { limit: "5242880" }, "too long")).toBe(
      "문서는 5,242,880자까지 가져올 수 있습니다",
    );
    expect(describeImportError("INVALID_SYNTAX", { detail: "line 3" }, "not valid")).toBe(
      "JSON·YAML 문법이 올바르지 않습니다 (line 3)",
    );
  });

  it("shows the server's message without a code, with an unknown code or a missing value", () => {
    expect(describeImportError(undefined, {}, "suiteId must not be null")).toBe("suiteId must not be null");
    expect(describeImportError("NEW_ERROR", {}, "something new")).toBe("something new");
    expect(describeImportError("SWAGGER_2", {}, "found swagger")).toBe("found swagger");
  });

  it("keeps warning and error codes apart", () => {
    expect(describeImportError("DEPRECATED", {}, "server message")).toBe("server message");
    expect(describeImportWarning({ code: "EMPTY", params: {}, message: "server message" })).toBe("server message");
  });
});
