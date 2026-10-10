import { describe, expect, it } from "vitest";

import {
  detailText,
  groupOperations,
  type OpenApiDocument,
  richText,
  schemaList,
  schemaNames,
  typeLabel,
} from "./openapi";

const doc: OpenApiDocument = {
  openapi: "3.1.0",
  info: { title: "Sentinel API", version: "v1" },
  tags: [{ name: "로그인", description: "세션" }, { name: "휴일", description: "쉬는 날" }, { name: "빈 묶음" }],
  paths: {
    "/api/holidays/{holidayId}": {
      delete: { summary: "삭제 (관리자).", tags: ["휴일"], responses: { "204": { description: "No Content" } } },
      get: {
        summary: "단건",
        tags: ["휴일"],
        responses: {
          "200": { content: { "application/json": { schema: { $ref: "#/components/schemas/HolidayItem" } } } },
        },
      },
    },
    "/api/holidays": {
      get: {
        summary: "그해 휴일, 날짜 순.",
        description: "그해 휴일, 날짜 순. 생략 시 올해",
        tags: ["휴일"],
        parameters: [{ name: "year", in: "query", schema: { type: "integer", format: "int32" } }],
        responses: {
          "200": {
            content: {
              "application/json": { schema: { type: "array", items: { $ref: "#/components/schemas/HolidayItem" } } },
            },
          },
        },
      },
      post: {
        summary: "등록 (관리자).",
        tags: ["휴일"],
        requestBody: {
          required: true,
          content: { "application/json": { schema: { $ref: "#/components/schemas/CreateHolidayRequest" } } },
        },
        responses: { "201": {} },
      },
    },
    "/api/sessions": { post: { summary: "로그인.", tags: ["로그인"], security: [], responses: { "200": {} } } },
    "/api/x": { get: { tags: ["추가 묶음"], responses: {} } },
  },
  components: {
    schemas: {
      HolidayItem: {
        type: "object",
        description: "휴일",
        properties: { id: { type: "integer", format: "int64" }, date: { type: "string", format: "date" } },
        required: ["id"],
      },
      CreateHolidayRequest: { type: "object", properties: { name: { type: "string", description: "이름" } } },
    },
  },
};

describe("groupOperations", () => {
  const groups = groupOperations(doc);

  it("groups by tag in the document order, unlisted tags last, empty tags dropped", () => {
    expect(groups.map((group) => group.name)).toEqual(["로그인", "휴일", "추가 묶음"]);
    expect(groups[1].description).toBe("쉬는 날");
    expect(groups[2].description).toBeNull();
  });

  it("orders operations by path and method and reads what the screen shows", () => {
    const holidays = groups[1].operations;
    expect(holidays.map((op) => `${op.method} ${op.path}`)).toEqual([
      "GET /api/holidays",
      "POST /api/holidays",
      "GET /api/holidays/{holidayId}",
      "DELETE /api/holidays/{holidayId}",
    ]);
    const list = holidays[0];
    expect(list.id).toBe("op-get-api-holidays");
    expect(list.summary).toBe("그해 휴일, 날짜 순");
    expect(holidays[2].summary).toBe("단건");
    expect(list.detail).toBe("생략 시 올해");
    expect(list.parameters).toEqual([
      { name: "year", in: "query", required: false, type: "integer", description: null },
    ]);
    expect(list.responses).toEqual([{ status: "200", type: "HolidayItem[]" }]);
    expect(holidays[1].requestBody).toEqual({ type: "CreateHolidayRequest", required: true });
    expect(holidays[1].responses).toEqual([{ status: "201", type: null }]);
    expect(holidays[2].id).toBe("op-get-api-holidays-holidayid");
  });

  it("marks requests that need no login and names operations without a summary", () => {
    expect(groups[0].operations[0].open).toBe(true);
    expect(groups[1].operations[0].open).toBe(false);
    expect(groups[2].operations[0].summary).toBe("GET /api/x");
  });
});

describe("typeLabel", () => {
  it.each([
    [undefined, "any"],
    [{ $ref: "#/components/schemas/IdName" }, "IdName"],
    [{ type: "array", items: { $ref: "#/components/schemas/RunSummary" } }, "RunSummary[]"],
    [{ type: "string", enum: ["A", "B"] }, "A | B"],
    [{ type: "string", format: "date-time" }, "date-time"],
    [{ type: "string", format: "email" }, "string"],
    [{ type: ["string", "null"] }, "string"],
    [{ type: "object", additionalProperties: { type: "string" } }, "map<string, string>"],
    [{ type: "object", additionalProperties: { type: "array", items: { type: "string" } } }, "map<string, string[]>"],
    [{ type: "object" }, "object"],
    [{ oneOf: [{ $ref: "#/components/schemas/A" }, { $ref: "#/components/schemas/B" }] }, "A | B"],
  ])("%j → %s", (schema, label) => {
    expect(typeLabel(schema as never)).toBe(label);
  });
});

describe("schemaNames", () => {
  it("finds the data formats in a type label", () => {
    expect(schemaNames("RunSummary[]")).toEqual(["RunSummary"]);
    expect(schemaNames("map<string, string[]>")).toEqual([]);
    expect(schemaNames("A | B")).toEqual(["A", "B"]);
  });
});

describe("schemaList", () => {
  it("lists data formats by name with their fields", () => {
    expect(schemaList(doc)).toEqual([
      {
        name: "CreateHolidayRequest",
        description: null,
        fields: [{ name: "name", type: "string", required: false, description: "이름" }],
      },
      {
        name: "HolidayItem",
        description: "휴일",
        fields: [
          { name: "id", type: "integer", required: true, description: null },
          { name: "date", type: "date", required: false, description: null },
        ],
      },
    ]);
  });
});

describe("detailText", () => {
  it("drops the summary repeated at the start of the description", () => {
    expect(detailText("단건.", "단건. 끝난 점검 포함")).toBe("끝난 점검 포함");
    expect(detailText("단건.", "단건.")).toBeNull();
    expect(detailText("단건.", "다른 설명")).toBe("다른 설명");
    expect(detailText("단건.", undefined)).toBeNull();
  });
});

describe("richText", () => {
  it("keeps code spans and strips other markup without rendering HTML", () => {
    expect(richText('본문 <code>{"enabled": false}</code>는 <b>제거</b> &amp; 유지')).toEqual([
      { text: "본문 ", code: false },
      { text: '{"enabled": false}', code: true },
      { text: "는 제거 & 유지", code: false },
    ]);
    expect(richText("<script>alert(1)</script>끝")).toEqual([{ text: "alert(1)끝", code: false }]);
    expect(richText("&lt;code&gt;")).toEqual([{ text: "<code>", code: false }]);
  });
});
