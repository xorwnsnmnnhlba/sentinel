import { http, HttpResponse } from "msw";
import { refresh } from "next/cache";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { problem, server, springUrl } from "@/test/msw";

import { createHoliday, deleteHoliday } from "./holidays";

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
