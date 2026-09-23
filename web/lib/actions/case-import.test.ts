import { http, HttpResponse } from "msw";
import { refresh } from "next/cache";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { CaseImportPreview } from "@/lib/api/types";
import { MAX_DOCUMENT_LENGTH } from "@/lib/case-import";
import { problem, server, springUrl } from "@/test/msw";

import { importCases, previewCaseImport } from "./case-import";

// refresh()는 Server Action 실행 중에만 호출 가능. 호출 여부만 확인
vi.mock("next/cache", () => ({ refresh: vi.fn() }));

const preview: CaseImportPreview = {
  title: "Orders",
  version: "1",
  servers: [],
  cases: [
    {
      source: "GET /orders",
      name: "List orders",
      request: { method: "GET", path: "/orders", headers: {}, body: null },
      assertions: [{ type: "statusCode", anyOf: ["200"] }],
      warnings: [],
      conflict: false,
    },
  ],
  warnings: [],
};

function capture(path: string, response: () => Response) {
  const bodies: unknown[] = [];
  server.use(
    http.post(springUrl(path), async ({ request }) => {
      bodies.push(await request.json());
      return response();
    }),
  );
  return bodies;
}

beforeEach(() => {
  vi.spyOn(console, "error").mockImplementation(() => {});
});

afterEach(() => {
  vi.mocked(refresh).mockClear();
});

describe("previewCaseImport", () => {
  it("sends the document as an OpenAPI 3 preview request", async () => {
    const bodies = capture("/api/case-import-previews", () => HttpResponse.json(preview));

    await expect(previewCaseImport(3, { content: "openapi: 3.0.0", format: "OPENAPI_3" })).resolves.toEqual({
      preview,
    });

    expect(bodies).toEqual([{ suiteId: 3, format: "OPENAPI_3", content: "openapi: 3.0.0" }]);
  });

  it("sends a Postman collection with its format", async () => {
    const bodies = capture("/api/case-import-previews", () => HttpResponse.json(preview));

    await previewCaseImport(3, { content: '{"info": {}}', format: "POSTMAN" });

    expect(bodies).toEqual([{ suiteId: 3, format: "POSTMAN", content: '{"info": {}}' }]);
  });

  it("shows why Spring could not read the document", async () => {
    server.use(
      http.post(springUrl("/api/case-import-previews"), () =>
        problem(400, "only OpenAPI 3 documents are supported (found swagger 2.0)"),
      ),
    );

    await expect(previewCaseImport(3, { content: "swagger: '2.0'", format: "OPENAPI_3" })).resolves.toEqual({
      error: "문서를 읽지 못했습니다: only OpenAPI 3 documents are supported (found swagger 2.0)",
    });
  });

  it.each([
    [{ content: "", format: "OPENAPI_3" }, "문서를 붙여넣거나 파일을 선택해 주세요"],
    [{ content: "x".repeat(MAX_DOCUMENT_LENGTH + 1), format: "OPENAPI_3" }, "문서는 5MB까지 가져올 수 있습니다"],
    [{ content: "{}", format: "RAML" }, "문서 형식을 선택해 주세요"],
    [{ content: "{}" }, "문서 형식을 선택해 주세요"],
    ["openapi: 3.0.0", "문서 형식을 선택해 주세요"],
    [null, "문서 형식을 선택해 주세요"],
  ])("checks the document before calling Spring", async (document, error) => {
    // 처리하지 않은 요청은 MSW가 실패로 처리하므로 Spring 미호출도 함께 확인
    await expect(previewCaseImport(3, document as { content: string; format: "OPENAPI_3" })).resolves.toEqual({
      error,
    });
  });
});

describe("importCases", () => {
  const chosen = [
    { name: "List orders", request: { method: "GET", path: "/orders", headers: {}, body: null }, assertions: [] },
  ];

  it("registers the chosen cases and refreshes the suite", async () => {
    const bodies = capture("/api/case-imports", () => HttpResponse.json({ suiteId: 3, cases: [] }, { status: 201 }));

    // 화면 전용 속성은 전달하지 않음
    await expect(importCases(3, [{ ...chosen[0], source: "GET /orders", conflict: false }])).resolves.toEqual({});

    expect(bodies).toEqual([{ suiteId: 3, cases: chosen }]);
    expect(refresh).toHaveBeenCalledOnce();
  });

  it.each([
    [
      409,
      "test case name 'List orders' is already in use",
      "이미 사용 중인 이름이 있습니다: test case name 'List orders' is already in use",
    ],
    [
      400,
      "cases[0] 'List orders': path must start with '/'",
      "가져오지 못했습니다: cases[0] 'List orders': path must start with '/'",
    ],
    [404, "test case suite 3 not found", "스위트를 찾을 수 없습니다. 다른 곳에서 삭제되었을 수 있습니다"],
    [500, "boom", "요청을 처리하지 못했습니다. API 서버 상태를 확인해 주세요"],
  ])("maps Spring %i to a screen message", async (status, detail, error) => {
    server.use(http.post(springUrl("/api/case-imports"), () => problem(status, detail)));

    await expect(importCases(3, chosen)).resolves.toEqual({ error });
    expect(refresh).not.toHaveBeenCalled();
  });

  it.each([
    [[], "가져올 케이스를 선택해 주세요"],
    ["not a list", "가져올 케이스를 선택해 주세요"],
    [[{ name: "x" }], "요청 형식이 올바르지 않습니다"],
    [Array.from({ length: 501 }, () => chosen[0]), "한 번에 500건까지 가져올 수 있습니다"],
  ])("checks the cases before calling Spring", async (cases, error) => {
    await expect(importCases(3, cases)).resolves.toEqual({ error });
  });

  it("rejects an invalid bound suite id", async () => {
    await expect(importCases(0, chosen)).rejects.toThrow("invalid id");
  });
});
