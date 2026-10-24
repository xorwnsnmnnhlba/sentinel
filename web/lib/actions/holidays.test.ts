import { http, HttpResponse } from "msw";
import { refresh } from "next/cache";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { codedProblem, problem, server, springUrl } from "@/test/msw";

import { createHoliday, deleteHoliday, importHolidays } from "./holidays";

vi.mock("next/cache", () => ({ refresh: vi.fn() }));

function form(fields: Record<string, string>): FormData {
  const data = new FormData();
  Object.entries(fields).forEach(([name, value]) => data.set(name, value));
  return data;
}

function capture(method: "post" | "delete", path: string, response: () => Response) {
  const bodies: unknown[] = [];
  server.use(
    http[method](springUrl(path), async ({ request }) => {
      const text = await request.text();
      bodies.push(text === "" ? undefined : JSON.parse(text));
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

describe("createHoliday", () => {
  it("registers the holiday and reloads the list", async () => {
    const bodies = capture("post", "/api/holidays", () =>
      HttpResponse.json({ id: 1, date: "2026-10-09", name: "한글날" }, { status: 201 }),
    );

    await expect(createHoliday(undefined, form({ date: "2026-10-09", name: "  한글날 " }))).resolves.toEqual({});
    expect(bodies).toEqual([{ date: "2026-10-09", name: "한글날" }]);
    expect(refresh).toHaveBeenCalledOnce();
  });

  it("explains invalid input without calling Spring", async () => {
    const bodies = capture("post", "/api/holidays", () => HttpResponse.json({}, { status: 201 }));

    await expect(createHoliday(undefined, form({ date: "", name: "한글날" }))).resolves.toEqual({
      error: "날짜를 입력해 주세요",
    });
    await expect(createHoliday(undefined, form({ date: "2026-10-09" }))).resolves.toEqual({
      error: "이름을 입력해 주세요",
    });
    expect(bodies).toEqual([]);
  });

  it.each([
    [409, "holiday 2026-10-09 already exists", "2026-10-09은(는) 이미 휴일로 등록되어 있습니다"],
    [403, "administrator role is required", "휴일은 관리자만 등록·삭제할 수 있습니다"],
    [400, "name must not be blank", "입력값을 확인해 주세요: name must not be blank"],
  ])("explains a %i from Spring", async (status, detail, message) => {
    capture("post", "/api/holidays", () => problem(status, detail));

    await expect(createHoliday(undefined, form({ date: "2026-10-09", name: "한글날" }))).resolves.toEqual({
      error: message,
    });
    expect(refresh).not.toHaveBeenCalled();
  });

  it("names a coded error in Korean", async () => {
    capture("post", "/api/holidays", () =>
      codedProblem(400, "name must be at most 100 characters", "TOO_LONG", { target: "name", max: "100" }),
    );

    await expect(createHoliday(undefined, form({ date: "2026-10-09", name: "한글날" }))).resolves.toEqual({
      error: "이름: 100자 이하로 입력해 주세요",
    });
  });

  it("hides unexpected failures", async () => {
    capture("post", "/api/holidays", () => HttpResponse.error());

    await expect(createHoliday(undefined, form({ date: "2026-10-09", name: "한글날" }))).resolves.toEqual({
      error: "요청을 처리하지 못했습니다. API 서버 상태를 확인해 주세요",
    });
  });
});

describe("deleteHoliday", () => {
  it("deletes the holiday and reloads the list", async () => {
    const bodies = capture("delete", "/api/holidays/4", () => new HttpResponse(null, { status: 204 }));

    await expect(deleteHoliday(4)).resolves.toEqual({});
    expect(bodies).toEqual([undefined]);
    expect(refresh).toHaveBeenCalledOnce();
  });

  it("reloads the list when someone else already deleted it", async () => {
    capture("delete", "/api/holidays/4", () => problem(404, "holiday 4 not found"));

    await expect(deleteHoliday(4)).resolves.toEqual({ error: "이미 삭제된 휴일입니다" });
    expect(refresh).toHaveBeenCalledOnce();
  });

  it("rejects ids that did not come from the list", async () => {
    const bodies = capture("delete", "/api/holidays/0", () => new HttpResponse(null, { status: 204 }));

    await expect(deleteHoliday(0)).resolves.toEqual({ error: "요청 형식이 올바르지 않습니다" });
    await expect(deleteHoliday(1.5)).resolves.toEqual({ error: "요청 형식이 올바르지 않습니다" });
    expect(bodies).toEqual([]);
  });

  it("explains that only administrators can delete", async () => {
    capture("delete", "/api/holidays/4", () => problem(403, "administrator role is required"));

    await expect(deleteHoliday(4)).resolves.toEqual({ error: "휴일은 관리자만 등록·삭제할 수 있습니다" });
  });
});

describe("importHolidays", () => {
  it("sends the pasted lines as holidays and reports what was skipped", async () => {
    const bodies = capture("post", "/api/holiday-imports", () =>
      HttpResponse.json(
        {
          created: [{ id: 2, date: "2026-12-25", name: "성탄절" }],
          skipped: [{ id: 1, date: "2027-01-01", name: "신정" }],
        },
        { status: 201 },
      ),
    );

    await expect(importHolidays(undefined, form({ lines: "2026.12.25 성탄절\n\n2027-01-01 새해" }))).resolves.toEqual({
      created: [{ id: 2, date: "2026-12-25", name: "성탄절" }],
      skipped: [{ id: 1, date: "2027-01-01", name: "신정" }],
    });
    expect(bodies).toEqual([
      {
        holidays: [
          { date: "2026-12-25", name: "성탄절" },
          { date: "2027-01-01", name: "새해" },
        ],
      },
    ]);
    expect(refresh).toHaveBeenCalledOnce();
  });

  it("explains a line it cannot read without calling Spring", async () => {
    const bodies = capture("post", "/api/holiday-imports", () => HttpResponse.json({}, { status: 201 }));

    await expect(importHolidays(undefined, form({ lines: "2026-12-25 성탄절\n신정" }))).resolves.toEqual({
      error: "2번째 줄: 날짜를 읽을 수 없습니다 (예: 2026-12-25 성탄절)",
    });
    await expect(importHolidays(undefined, form({}))).resolves.toEqual({ error: "붙여 넣은 휴일이 없습니다" });
    expect(bodies).toEqual([]);
  });

  it("shows what Spring rejected", async () => {
    capture("post", "/api/holiday-imports", () => problem(403, "administrator role is required"));

    await expect(importHolidays(undefined, form({ lines: "2026-12-25 성탄절" }))).resolves.toEqual({
      error: "휴일은 관리자만 등록·삭제할 수 있습니다",
    });
    expect(refresh).not.toHaveBeenCalled();
  });
});
