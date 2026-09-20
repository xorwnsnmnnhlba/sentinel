import { http, HttpResponse } from "msw";
import { refresh } from "next/cache";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { problem, server, springUrl } from "@/test/msw";
import { captureRedirect } from "@/test/next";

import { type CaseDraft, emptyDraft } from "@/lib/case-draft";

import {
  createCase,
  createSchedule,
  createEnvironment,
  createProject,
  deleteCase,
  deleteProject,
  deleteSuite,
  runSuite,
  deleteSchedule,
  setScheduleEnabled,
  updateCase,
  updateEnvironment,
  updateScheduleCron,
  updateProject,
} from "./catalog";

// refresh()는 Server Action 실행 중에만 호출 가능. 호출 여부만 확인
vi.mock("next/cache", () => ({ refresh: vi.fn() }));

function form(fields: Record<string, string>): FormData {
  const data = new FormData();
  Object.entries(fields).forEach(([name, value]) => data.set(name, value));
  return data;
}

/** 지정 경로로 온 요청 본문 기록 후 응답 */
function capture(method: "post" | "patch" | "delete", path: string, response: () => Response) {
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

describe("createProject", () => {
  it("sends blank optional fields as null and redirects to the new project", async () => {
    const bodies = capture("post", "/api/projects", () =>
      HttpResponse.json({ id: 7, name: "billing", description: null }, { status: 201 }),
    );

    const target = await captureRedirect(() => createProject(undefined, form({ name: "billing", description: "  " })));

    expect(target).toBe("/projects/7");
    expect(bodies).toEqual([{ name: "billing", description: null }]);
  });

  it.each([
    [409, "project name 'billing' is already in use", "이미 사용 중인 이름입니다"],
    [400, "name must not be blank", "입력값을 확인해 주세요: name must not be blank"],
    [404, "project 1 not found", "대상을 찾을 수 없습니다. 다른 곳에서 삭제되었을 수 있습니다"],
    [500, "boom", "요청을 처리하지 못했습니다. API 서버 상태를 확인해 주세요"],
  ])("maps Spring %i to a screen message", async (status, detail, message) => {
    server.use(http.post(springUrl("/api/projects"), () => problem(status, detail)));

    await expect(createProject(undefined, form({ name: "billing" }))).resolves.toEqual({ error: message });
    expect(refresh).not.toHaveBeenCalled();
  });

  it("hides connection failures behind a fixed message", async () => {
    server.use(http.post(springUrl("/api/projects"), () => HttpResponse.error()));

    await expect(createProject(undefined, form({ name: "billing" }))).resolves.toEqual({
      error: "요청을 처리하지 못했습니다. API 서버 상태를 확인해 주세요",
    });
  });
});

describe("updateProject", () => {
  it("sends every field as typed so blanks are validated or clear the description", async () => {
    const bodies = capture("patch", "/api/projects/3", () =>
      HttpResponse.json({ id: 3, name: "billing", description: null }),
    );

    await expect(updateProject(3, undefined, form({ name: "billing", description: "" }))).resolves.toEqual({});

    expect(bodies).toEqual([{ name: "billing", description: "" }]);
    expect(refresh).toHaveBeenCalledOnce();
  });

  it.each([0, -1, 1.5, Number.NaN])("rejects invalid bound id %s before calling Spring", async (id) => {
    await expect(updateProject(id, undefined, form({ name: "x" }))).rejects.toThrow("invalid id");
  });
});

describe("environments", () => {
  it("parses variables JSON from the editor", async () => {
    const bodies = capture("post", "/api/environments", () => HttpResponse.json({}, { status: 201 }));

    await createEnvironment(
      2,
      undefined,
      form({ name: "dev", baseUrl: "https://gw", variables: JSON.stringify({ userId: "42" }) }),
    );

    expect(bodies).toEqual([{ projectId: 2, name: "dev", baseUrl: "https://gw", variables: { userId: "42" } }]);
  });

  it("treats a missing variables field as no variables", async () => {
    const bodies = capture("patch", "/api/environments/4", () => HttpResponse.json({}));

    await updateEnvironment(4, undefined, form({ name: "dev", baseUrl: "https://gw" }));

    expect(bodies).toEqual([{ name: "dev", baseUrl: "https://gw", variables: {} }]);
  });

  it.each([
    ["not json", "변수 형식이 올바르지 않습니다"],
    ["[1, 2]", "변수 형식이 올바르지 않습니다"],
    ["null", "변수 형식이 올바르지 않습니다"],
    ['{"userId": 42}', "변수 값은 문자열이어야 합니다"],
  ])("rejects variables %s without calling Spring", async (variables, message) => {
    await expect(
      createEnvironment(2, undefined, form({ name: "dev", baseUrl: "https://gw", variables })),
    ).resolves.toEqual({ error: message });
  });
});

describe("deletes", () => {
  it("refreshes the current page after deleting a case", async () => {
    capture("delete", "/api/cases/5", () => new HttpResponse(null, { status: 204 }));

    await expect(deleteCase(5)).resolves.toEqual({});
    expect(refresh).toHaveBeenCalledOnce();
  });

  it("redirects to the project list after deleting a project", async () => {
    capture("delete", "/api/projects/3", () => new HttpResponse(null, { status: 204 }));

    await expect(captureRedirect(() => deleteProject(3))).resolves.toBe("/projects");
  });

  it("stays on the page when a suite is deleted from the project page", async () => {
    capture("delete", "/api/suites/8", () => new HttpResponse(null, { status: 204 }));

    await expect(deleteSuite(8)).resolves.toEqual({});
  });

  it("moves to the project when a suite is deleted from its own page", async () => {
    capture("delete", "/api/suites/8", () => new HttpResponse(null, { status: 204 }));

    await expect(captureRedirect(() => deleteSuite(8, 2))).resolves.toBe("/projects/2");
  });

  it("does not redirect when the suite delete fails", async () => {
    server.use(http.delete(springUrl("/api/suites/8"), () => problem(404, "test suite 8 not found")));

    await expect(deleteSuite(8, 2)).resolves.toEqual({
      error: "대상을 찾을 수 없습니다. 다른 곳에서 삭제되었을 수 있습니다",
    });
  });
});

describe("runSuite", () => {
  it("requires an environment", async () => {
    await expect(runSuite(1, undefined, form({}))).resolves.toEqual({ error: "실행할 환경을 선택해 주세요" });
  });

  it("creates a run and opens its result", async () => {
    const bodies = capture("post", "/api/runs", () => HttpResponse.json({ id: 42 }, { status: 201 }));

    await expect(captureRedirect(() => runSuite(1, undefined, form({ environmentId: "3" })))).resolves.toBe("/runs/42");
    expect(bodies).toEqual([{ suiteId: 1, environmentId: 3 }]);
  });
});

describe("cases", () => {
  const draft: CaseDraft = {
    ...emptyDraft(),
    name: "health",
    method: "POST",
    path: "/health",
    headers: [{ name: "Accept", value: "application/json" }],
    body: "{}",
    assertions: [{ type: "jsonPath", path: "$.status", operator: "EQUALS", expected: "UP" }],
  };

  it("creates a case in the suite and returns to the suite page", async () => {
    const bodies = capture("post", "/api/cases", () => HttpResponse.json({ id: 9 }, { status: 201 }));

    await expect(captureRedirect(() => createCase(4, draft))).resolves.toBe("/suites/4");
    expect(bodies).toEqual([
      {
        suiteId: 4,
        name: "health",
        request: { method: "POST", path: "/health", headers: { Accept: ["application/json"] }, body: "{}" },
        assertions: [{ type: "jsonPath", path: "$.status", operator: "EQUALS", expected: "UP" }],
      },
    ]);
  });

  it("replaces the whole case on update without sending suiteId", async () => {
    const bodies = capture("patch", "/api/cases/9", () => HttpResponse.json({ id: 9 }));

    await expect(captureRedirect(() => updateCase(9, 4, { ...draft, assertions: [] }))).resolves.toBe("/suites/4");
    expect(bodies).toEqual([
      {
        name: "health",
        request: { method: "POST", path: "/health", headers: { Accept: ["application/json"] }, body: "{}" },
        assertions: [],
      },
    ]);
  });

  it("revalidates on the server and skips Spring for invalid drafts", async () => {
    await expect(createCase(4, { ...draft, path: "health" })).resolves.toEqual({
      errors: ["경로는 / 또는 {{변수}}로 시작해야 합니다 (기본 URL 뒤에 붙는 상대 경로)"],
    });
  });

  it("rejects malformed input sent without the form", async () => {
    await expect(createCase(4, { name: "x" } as unknown as CaseDraft)).resolves.toEqual({
      errors: ["요청 형식이 올바르지 않습니다"],
    });
  });

  it("shows Spring errors", async () => {
    server.use(http.post(springUrl("/api/cases"), () => problem(409, "test case name 'health' is already in use")));

    await expect(createCase(4, draft)).resolves.toEqual({ errors: ["이미 사용 중인 이름입니다"] });
  });
});

describe("schedules", () => {
  it("creates a schedule for the suite and environment", async () => {
    const bodies = capture("post", "/api/schedules", () => HttpResponse.json({ id: 5 }, { status: 201 }));

    await expect(createSchedule(2, undefined, form({ environmentId: "3", cron: "10 9 * * 1-5" }))).resolves.toEqual({});

    expect(bodies).toEqual([{ suiteId: 2, environmentId: 3, cron: "10 9 * * 1-5" }]);
    expect(refresh).toHaveBeenCalledOnce();
  });

  it("requires an environment", async () => {
    await expect(createSchedule(2, undefined, form({ cron: "10 9 * * 1-5" }))).resolves.toEqual({
      error: "실행할 환경을 선택해 주세요",
    });
  });

  it("shows the Spring message for an invalid cron", async () => {
    server.use(http.post(springUrl("/api/schedules"), () => problem(400, "cron must have 5 fields")));

    await expect(createSchedule(2, undefined, form({ environmentId: "3", cron: "0 10 9 * * *" }))).resolves.toEqual({
      error: "입력값을 확인해 주세요: cron must have 5 fields",
    });
  });

  it("sends only cron when editing", async () => {
    const bodies = capture("patch", "/api/schedules/5", () => HttpResponse.json({ id: 5 }));

    await updateScheduleCron(5, undefined, form({ cron: "30 17 * * 1-5" }));

    expect(bodies).toEqual([{ cron: "30 17 * * 1-5" }]);
  });

  it.each([true, false])("sends enabled=%s", async (enabled) => {
    const bodies = capture("patch", "/api/schedules/5", () => HttpResponse.json({ id: 5 }));

    await expect(setScheduleEnabled(5, enabled)).resolves.toEqual({});

    expect(bodies).toEqual([{ enabled }]);
  });

  it("deletes a schedule", async () => {
    capture("delete", "/api/schedules/5", () => new HttpResponse(null, { status: 204 }));

    await expect(deleteSchedule(5)).resolves.toEqual({});
    expect(refresh).toHaveBeenCalledOnce();
  });
});
