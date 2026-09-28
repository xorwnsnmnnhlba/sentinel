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
  runCase,
  runSuite,
  runThroughCase,
  deleteSchedule,
  duplicateCase,
  reorderCases,
  setScheduleEnabled,
  updateCase,
  updateEnvironment,
  updateSchedule,
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

    expect(bodies).toEqual([
      { projectId: 2, name: "dev", baseUrl: "https://gw", variables: { userId: "42" }, secrets: {} },
    ]);
  });

  it("treats a missing variables field as no variables", async () => {
    const bodies = capture("patch", "/api/environments/4", () => HttpResponse.json({}));

    await updateEnvironment(4, undefined, form({ name: "dev", baseUrl: "https://gw" }));

    expect(bodies).toEqual([{ name: "dev", baseUrl: "https://gw", variables: {}, secrets: {} }]);
  });

  it("sends secrets by name, with null for removals", async () => {
    const bodies = capture("patch", "/api/environments/4", () => HttpResponse.json({}));

    await updateEnvironment(
      4,
      undefined,
      form({ name: "dev", baseUrl: "https://gw", secrets: JSON.stringify({ token: "value", old: null }) }),
    );

    expect(bodies).toEqual([
      { name: "dev", baseUrl: "https://gw", variables: {}, secrets: { token: "value", old: null } },
    ]);
  });

  it.each([
    ["not json", "시크릿 형식이 올바르지 않습니다"],
    ["[1]", "시크릿 형식이 올바르지 않습니다"],
    ['{"token": 42}', "시크릿 값은 문자열이어야 합니다"],
    // 복제 시 이름만 채운 행을 비운 채 저장하면 빈 시크릿이 되므로 거부. 값은 메시지에 넣지 않음
    ['{"apiKey": "k", "token": ""}', "시크릿 token의 값을 입력하거나 그 행을 지워 주세요"],
  ])("rejects secrets %s without calling Spring", async (secrets, message) => {
    await expect(
      createEnvironment(2, undefined, form({ name: "dev", baseUrl: "https://gw", secrets })),
    ).resolves.toEqual({ error: message });
  });

  it("explains that the server has no encryption key", async () => {
    server.use(
      http.post(springUrl("/api/environments"), () =>
        problem(400, "secrets are disabled: set sentinel.secret.key (SENTINEL_SECRET_KEY) to use them"),
      ),
    );

    await expect(
      createEnvironment(2, undefined, form({ name: "dev", baseUrl: "https://gw", secrets: '{"token": "v"}' })),
    ).resolves.toEqual({
      error: "시크릿 기능이 꺼져 있습니다. 서버에 암호화 키(SENTINEL_SECRET_KEY) 설정이 필요합니다",
    });
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

describe("runThroughCase", () => {
  it("runs the suite from the start through the case and opens the result", async () => {
    const bodies = capture("post", "/api/runs", () => HttpResponse.json({ id: 44 }, { status: 201 }));

    await expect(captureRedirect(() => runThroughCase(7, undefined, form({ environmentId: "3" })))).resolves.toBe(
      "/runs/44",
    );
    expect(bodies).toEqual([{ throughCaseId: 7, environmentId: 3 }]);
  });

  it("requires an environment", async () => {
    await expect(runThroughCase(7, undefined, form({ environmentId: "" }))).resolves.toEqual({
      error: "실행할 환경을 선택해 주세요",
    });
  });
});

describe("runCase", () => {
  it("requires an environment", async () => {
    await expect(runCase(7, undefined, form({ environmentId: "0" }))).resolves.toEqual({
      error: "실행할 환경을 선택해 주세요",
    });
  });

  it("runs only the case and opens its result", async () => {
    const bodies = capture("post", "/api/runs", () => HttpResponse.json({ id: 43 }, { status: 201 }));

    await expect(captureRedirect(() => runCase(7, undefined, form({ environmentId: "3" })))).resolves.toBe("/runs/43");
    // 스위트 대신 케이스만 지정 (API는 둘 중 하나만 허용)
    expect(bodies).toEqual([{ caseId: 7, environmentId: 3 }]);
  });

  it("asks to retry later when too many runs are in progress", async () => {
    capture("post", "/api/runs", () => problem(429, "4 manual runs are already in progress"));

    await expect(runCase(7, undefined, form({ environmentId: "3" }))).resolves.toEqual({
      error: "진행 중인 실행이 많아 지금은 시작할 수 없습니다. 실행이 끝난 뒤 다시 시도해 주세요",
    });
  });

  it("tells when the case was deleted meanwhile", async () => {
    capture("post", "/api/runs", () => problem(404, "test case 7 not found"));

    await expect(runCase(7, undefined, form({ environmentId: "3" }))).resolves.toEqual({
      error: "대상을 찾을 수 없습니다. 다른 곳에서 삭제되었을 수 있습니다",
    });
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
    extractors: [{ name: " token ", from: "BODY", expression: "$.token", secret: true }],
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
        extractors: [{ name: "token", from: "BODY", expression: "$.token", secret: true }],
      },
    ]);
  });

  it("replaces the whole case on update without sending suiteId", async () => {
    const bodies = capture("patch", "/api/cases/9", () => HttpResponse.json({ id: 9 }));

    await expect(captureRedirect(() => updateCase(9, 4, { ...draft, assertions: [], extractors: [] }))).resolves.toBe(
      "/suites/4",
    );
    expect(bodies).toEqual([
      {
        name: "health",
        request: { method: "POST", path: "/health", headers: { Accept: ["application/json"] }, body: "{}" },
        assertions: [],
        extractors: [],
      },
    ]);
  });

  it("saves and then runs only this case, opening its result", async () => {
    const saved = capture("patch", "/api/cases/9", () => HttpResponse.json({ id: 9 }));
    const runs = capture("post", "/api/runs", () => HttpResponse.json({ id: 77 }, { status: 201 }));

    await expect(captureRedirect(() => updateCase(9, 4, draft, 3))).resolves.toBe("/runs/77");
    expect(saved).toHaveLength(1);
    expect(runs).toEqual([{ caseId: 9, environmentId: 3 }]);
  });

  it("saves and then runs from the start of the suite through this case", async () => {
    capture("patch", "/api/cases/9", () => HttpResponse.json({ id: 9 }));
    const runs = capture("post", "/api/runs", () => HttpResponse.json({ id: 78 }, { status: 201 }));

    await expect(captureRedirect(() => updateCase(9, 4, draft, 3, true))).resolves.toBe("/runs/78");
    expect(runs).toEqual([{ throughCaseId: 9, environmentId: 3 }]);
  });

  it("runs a newly created case by the id Spring gave it", async () => {
    capture("post", "/api/cases", () => HttpResponse.json({ id: 12 }, { status: 201 }));
    const runs = capture("post", "/api/runs", () => HttpResponse.json({ id: 78 }, { status: 201 }));

    await expect(captureRedirect(() => createCase(4, draft, 3))).resolves.toBe("/runs/78");
    expect(runs).toEqual([{ caseId: 12, environmentId: 3 }]);
  });

  it("moves a created case to its edit page when only the run fails, so it is not created twice", async () => {
    capture("post", "/api/cases", () => HttpResponse.json({ id: 12 }, { status: 201 }));
    capture("post", "/api/runs", () => problem(404, "environment 3 not found"));

    await expect(captureRedirect(() => createCase(4, draft, 3))).resolves.toBe("/cases/12?notice=run-failed");
  });

  it("keeps an updated case on the form when only the run fails", async () => {
    capture("patch", "/api/cases/9", () => HttpResponse.json({ id: 9 }));
    capture("post", "/api/runs", () => problem(404, "environment 3 not found"));

    await expect(updateCase(9, 4, draft, 3)).resolves.toEqual({
      errors: ["저장했지만 실행하지 못했습니다. 대상을 찾을 수 없습니다. 다른 곳에서 삭제되었을 수 있습니다"],
    });
  });

  it.each([0, -1, 1.5, "3"])("rejects the environment %s before saving", async (runIn) => {
    // 저장 요청도 보내지 않음 (MSW는 처리하지 않은 요청을 실패로 처리)
    await expect(updateCase(9, 4, draft, runIn as number)).resolves.toEqual({
      errors: ["실행할 환경을 선택해 주세요"],
    });
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

  it("sends cron and environment when editing", async () => {
    const bodies = capture("patch", "/api/schedules/5", () => HttpResponse.json({ id: 5 }));

    await updateSchedule(5, undefined, form({ cron: "30 17 * * 1-5", environmentId: "4" }));

    expect(bodies).toEqual([{ cron: "30 17 * * 1-5", environmentId: 4 }]);
  });

  it("requires an environment when editing", async () => {
    await expect(updateSchedule(5, undefined, form({ cron: "30 17 * * 1-5" }))).resolves.toEqual({
      error: "실행할 환경을 선택해 주세요",
    });
  });

  it("shows the Spring message when the environment belongs to another project", async () => {
    server.use(
      http.patch(springUrl("/api/schedules/5"), () =>
        problem(400, "suite and environment must belong to the same project"),
      ),
    );

    await expect(updateSchedule(5, undefined, form({ cron: "30 17 * * 1-5", environmentId: "9" }))).resolves.toEqual({
      error: "입력값을 확인해 주세요: suite and environment must belong to the same project",
    });
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

describe("reorderCases", () => {
  function order(...caseIds: string[]) {
    const data = new FormData();
    caseIds.forEach((caseId) => data.append("caseId", caseId));
    return data;
  }

  it("saves the submitted order as the suite's case order", async () => {
    const bodies = capture("patch", "/api/suites/4", () => HttpResponse.json({ id: 4 }));

    await expect(reorderCases(4, undefined, order("13", "11", "12"))).resolves.toEqual({});
    expect(bodies).toEqual([{ caseOrder: [13, 11, 12] }]);
    expect(refresh).toHaveBeenCalledOnce();
  });

  it("explains a list that changed meanwhile and reloads the page", async () => {
    capture("patch", "/api/suites/4", () => problem(409, "the cases of test suite 4 have changed"));

    await expect(reorderCases(4, undefined, order("11"))).resolves.toEqual({
      error: "그사이 케이스가 추가되거나 삭제되었습니다. 창을 닫고 다시 열어 순서를 정해 주세요",
    });
    expect(refresh).toHaveBeenCalledOnce();
  });

  it.each([[[]], [["11", "abc"]], [["0"]]])("rejects malformed ids %j without calling Spring", async (caseIds) => {
    const bodies = capture("patch", "/api/suites/4", () => HttpResponse.json({ id: 4 }));

    await expect(reorderCases(4, undefined, order(...caseIds))).resolves.toEqual({
      error: "요청 형식이 올바르지 않습니다",
    });
    expect(bodies).toEqual([]);
  });
});

describe("duplicateCase", () => {
  const original = {
    id: 7,
    suiteId: 4,
    name: "주문 조회",
    request: { method: "GET", path: "/orders/{{id}}", headers: { Authorization: ["Bearer {{token}}"] }, body: null },
    assertions: [{ type: "statusCode", anyOf: ["200"] }],
    extractors: [{ name: "orderNo", from: "BODY", expression: "$.orderNo", secret: false }],
  };

  function existing(names: string[]) {
    server.use(
      http.get(springUrl("/api/cases/7"), () => HttpResponse.json(original)),
      http.get(springUrl("/api/cases"), ({ request }) => {
        expect(new URL(request.url).searchParams.get("suiteId")).toBe("4");
        return HttpResponse.json(names.map((name, index) => ({ ...original, id: 100 + index, name })));
      }),
    );
  }

  it("copies the request, rules and extractors right after the original under a free name and opens the copy", async () => {
    existing(["주문 조회", "주문 조회 (복사)"]);
    const bodies = capture("post", "/api/cases", () => HttpResponse.json({ id: 20 }, { status: 201 }));

    await expect(captureRedirect(() => duplicateCase(7))).resolves.toBe("/cases/20");
    expect(bodies).toEqual([
      {
        suiteId: 4,
        name: "주문 조회 (복사 2)",
        request: original.request,
        assertions: original.assertions,
        extractors: original.extractors,
        afterCaseId: 7,
      },
    ]);
  });

  it("tells when the original was deleted meanwhile", async () => {
    server.use(http.get(springUrl("/api/cases/7"), () => problem(404, "test case 7 not found")));

    await expect(duplicateCase(7)).resolves.toEqual({
      error: "대상을 찾을 수 없습니다. 다른 곳에서 삭제되었을 수 있습니다",
    });
  });

  it("reports a name taken by someone else in between", async () => {
    existing(["주문 조회"]);
    capture("post", "/api/cases", () => problem(409, "test case name '주문 조회 (복사)' is already in use"));

    await expect(duplicateCase(7)).resolves.toEqual({ error: "이미 사용 중인 이름입니다" });
  });

  it("sends an expired session to the login page instead of showing an error", async () => {
    server.use(http.get(springUrl("/api/cases/7"), () => problem(401, "authentication required")));

    await expect(captureRedirect(() => duplicateCase(7))).resolves.toBe("/login");
  });
});
