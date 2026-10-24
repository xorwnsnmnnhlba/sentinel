import { http, HttpResponse } from "msw";
import { refresh } from "next/cache";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { problem, server, springUrl } from "@/test/msw";
import { captureRedirect } from "@/test/next";

import { type CaseDraft, emptyDraft } from "@/lib/case-draft";

import {
  cancelRun,
  createCase,
  createSchedule,
  createEnvironment,
  createMaintenanceWindow,
  createProject,
  deleteCase,
  deleteMaintenanceWindow,
  deleteProject,
  deleteSuite,
  runCase,
  runSuite,
  runThroughCase,
  deleteSchedule,
  duplicateCase,
  removeProjectWebhook,
  reorderCases,
  rerunFailed,
  sendDefaultTestNotification,
  sendTestNotification,
  setProjectWebhook,
  setScheduleEnabled,
  updateCase,
  updateEnvironment,
  updateMaintenanceWindow,
  updateSchedule,
  updateSchedules,
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

  it("sends the version the edit dialog was opened with", async () => {
    const bodies = capture("patch", "/api/environments/4", () => HttpResponse.json({}));

    await updateEnvironment(4, undefined, form({ name: "dev", baseUrl: "https://gw", version: "7" }));

    expect(bodies).toEqual([{ name: "dev", baseUrl: "https://gw", variables: {}, secrets: {}, version: 7 }]);
  });

  it.each(["", "-1", "1.5", "abc"])("leaves out a malformed version %j so Spring skips the check", async (version) => {
    const bodies = capture("patch", "/api/environments/4", () => HttpResponse.json({}));

    await updateEnvironment(4, undefined, form({ name: "dev", baseUrl: "https://gw", version }));

    expect(bodies).toEqual([{ name: "dev", baseUrl: "https://gw", variables: {}, secrets: {} }]);
  });

  it("explains that someone else saved the environment first and reloads the page", async () => {
    server.use(
      http.patch(springUrl("/api/environments/4"), () =>
        problem(409, "modified by another request; reload and try again"),
      ),
    );

    await expect(
      updateEnvironment(4, undefined, form({ name: "dev", baseUrl: "https://gw", version: "7" })),
    ).resolves.toEqual({
      error: "다른 곳에서 먼저 수정했습니다. 최신 내용을 확인한 뒤 다시 시도해 주세요",
    });
    expect(refresh).toHaveBeenCalledOnce();
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
        HttpResponse.json(
          {
            status: 400,
            detail: "secrets are disabled: set sentinel.secret.key (SENTINEL_SECRET_KEY) to use them",
            code: "SECRETS_DISABLED",
            params: {},
          },
          { status: 400 },
        ),
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

describe("cancelRun", () => {
  it("asks Spring to cancel the run and reloads the page", async () => {
    const bodies = capture("patch", "/api/runs/43", () => HttpResponse.json({ id: 43, status: "CANCELED" }));

    await expect(cancelRun(43)).resolves.toEqual({});
    expect(bodies).toEqual([{ status: "CANCELED" }]);
    expect(refresh).toHaveBeenCalledOnce();
  });

  it("explains a run that finished meanwhile and reloads to show it", async () => {
    capture("patch", "/api/runs/43", () => problem(409, "test run 43 is already COMPLETED"));

    await expect(cancelRun(43)).resolves.toEqual({ error: "이미 끝난 실행입니다" });
    expect(refresh).toHaveBeenCalledOnce();
  });

  it("tells when the run is gone", async () => {
    capture("patch", "/api/runs/43", () => problem(404, "test run 43 not found"));

    await expect(cancelRun(43)).resolves.toEqual({
      error: "대상을 찾을 수 없습니다. 다른 곳에서 삭제되었을 수 있습니다",
    });
  });
});

describe("project webhook", () => {
  it("saves the trimmed address and reloads the page", async () => {
    const bodies = capture("patch", "/api/projects/3", () => HttpResponse.json({ id: 3, webhookConfigured: true }));

    await expect(
      setProjectWebhook(3, undefined, form({ webhookUrl: "  https://hooks.example.com/services/T1  " })),
    ).resolves.toEqual({});
    expect(bodies).toEqual([{ webhookUrl: "https://hooks.example.com/services/T1" }]);
    expect(refresh).toHaveBeenCalledOnce();
  });

  it("explains an invalid address without calling Spring", async () => {
    const bodies = capture("patch", "/api/projects/3", () => HttpResponse.json({ id: 3 }));

    await expect(setProjectWebhook(3, undefined, form({ webhookUrl: "hooks.example.com" }))).resolves.toEqual({
      error: "http:// 또는 https://로 시작하는 주소를 입력해 주세요",
    });
    expect(bodies).toEqual([]);
  });

  it("goes back to the default channel by clearing the address", async () => {
    const bodies = capture("patch", "/api/projects/3", () => HttpResponse.json({ id: 3, webhookConfigured: false }));

    await expect(removeProjectWebhook(3)).resolves.toEqual({});
    expect(bodies).toEqual([{ webhookUrl: "" }]);
  });

  it("returns how the test notification went", async () => {
    const bodies = capture("post", "/api/notification-deliveries", () =>
      HttpResponse.json(
        {
          id: 9,
          channel: "PROJECT",
          project: { id: 3, name: "billing" },
          delivered: false,
          statusCode: 404,
          error: null,
        },
        { status: 201 },
      ),
    );

    await expect(sendTestNotification(3)).resolves.toEqual({
      delivery: { delivered: false, statusCode: 404, error: null },
    });
    expect(bodies).toEqual([{ projectId: 3 }]);
  });

  it("explains a missing or unreadable webhook", async () => {
    capture("post", "/api/notification-deliveries", () => problem(409, "project 3 has no webhook"));
    await expect(sendTestNotification(3)).resolves.toEqual({ error: "지정된 Webhook이 없습니다" });
    expect(refresh).toHaveBeenCalledOnce();

    capture("post", "/api/notification-deliveries", () => problem(400, "webhook of project 3 cannot be read"));
    await expect(sendTestNotification(3)).resolves.toEqual({
      error: "Webhook 주소를 읽을 수 없습니다. 서버 암호화 키가 바뀌었다면 주소를 다시 지정해 주세요",
    });
  });
});

describe("sendDefaultTestNotification", () => {
  it("returns how the test notification went and reloads the history", async () => {
    const bodies = capture("post", "/api/notification-deliveries", () =>
      HttpResponse.json(
        { id: 10, channel: "DEFAULT", project: null, delivered: true, statusCode: 200, error: null },
        { status: 201 },
      ),
    );

    await expect(sendDefaultTestNotification()).resolves.toEqual({
      delivery: { delivered: true, statusCode: 200, error: null },
    });
    // 프로젝트 없이 보내면 기본 채널
    expect(bodies).toEqual([{}]);
    expect(refresh).toHaveBeenCalledOnce();
  });

  it("explains a missing default channel or a member trying it", async () => {
    capture("post", "/api/notification-deliveries", () =>
      problem(409, "default notification channel is not configured"),
    );
    await expect(sendDefaultTestNotification()).resolves.toEqual({
      error: "기본 알림 채널 주소(SENTINEL_WEBHOOK_URL)가 설정되지 않아 보낼 수 없습니다",
    });
    expect(refresh).toHaveBeenCalledOnce();

    capture("post", "/api/notification-deliveries", () => problem(403, "administrator role is required"));
    await expect(sendDefaultTestNotification()).resolves.toEqual({
      error: "기본 채널 테스트 알림은 관리자만 보낼 수 있습니다",
    });
  });
});

describe("maintenance windows", () => {
  // 끝 시각이 지나지 않도록 먼 미래
  const period = { startsAt: "2099-09-29T10:00", endsAt: "2099-09-29T11:30", reason: "  GW 배포 " };

  it("registers the window in KST and reloads the page", async () => {
    const bodies = capture("post", "/api/maintenance-windows", () => HttpResponse.json({ id: 9 }, { status: 201 }));

    await expect(createMaintenanceWindow(4, undefined, form(period))).resolves.toEqual({});
    expect(bodies).toEqual([
      {
        environmentId: 4,
        startsAt: "2099-09-29T01:00:00.000Z",
        endsAt: "2099-09-29T02:30:00.000Z",
        reason: "GW 배포",
      },
    ]);
    expect(refresh).toHaveBeenCalledOnce();
  });

  it.each([
    [{ ...period, endsAt: "" }, "시작·끝 시각을 입력해 주세요"],
    [{ ...period, endsAt: "2099-09-29T10:00" }, "끝 시각은 시작 시각보다 뒤여야 합니다"],
    [{ ...period, endsAt: "2099-10-07T10:00" }, "점검 시간은 최대 7일입니다"],
  ])("explains an invalid period without calling Spring: %j", async (fields, message) => {
    const bodies = capture("post", "/api/maintenance-windows", () => HttpResponse.json({ id: 9 }, { status: 201 }));

    await expect(createMaintenanceWindow(4, undefined, form(fields))).resolves.toEqual({ error: message });
    expect(bodies).toEqual([]);
  });

  it("changes the window, sending an empty reason to clear it", async () => {
    const bodies = capture("patch", "/api/maintenance-windows/9", () => HttpResponse.json({ id: 9 }));

    await expect(updateMaintenanceWindow(9, undefined, form({ ...period, reason: "" }))).resolves.toEqual({});
    expect(bodies).toEqual([{ startsAt: "2099-09-29T01:00:00.000Z", endsAt: "2099-09-29T02:30:00.000Z", reason: "" }]);
    expect(refresh).toHaveBeenCalledOnce();
  });

  it("explains a window that ended meanwhile and reloads to show it", async () => {
    capture("patch", "/api/maintenance-windows/9", () => problem(409, "maintenance window 9 has already ended"));

    await expect(updateMaintenanceWindow(9, undefined, form(period))).resolves.toEqual({
      error: "이미 끝난 점검입니다",
    });
    expect(refresh).toHaveBeenCalledOnce();
  });

  it("ends or cancels the window by deleting it", async () => {
    const bodies = capture("delete", "/api/maintenance-windows/9", () => new HttpResponse(null, { status: 204 }));

    await expect(deleteMaintenanceWindow(9)).resolves.toEqual({});
    expect(bodies).toEqual([undefined]);
    expect(refresh).toHaveBeenCalledOnce();
  });
});

describe("rerunFailed", () => {
  it("reruns the failed cases in the run's environment and opens the new run", async () => {
    const bodies = capture("post", "/api/runs", () => HttpResponse.json({ id: 45 }, { status: 202 }));

    await expect(captureRedirect(() => rerunFailed(43))).resolves.toBe("/runs/45");
    expect(bodies).toEqual([{ rerunOf: 43 }]);
  });

  it.each([
    ["test run 43 is still running", "아직 진행 중인 실행입니다. 끝난 뒤 다시 실행해 주세요"],
    [
      "test run 43 has no failed cases to rerun",
      "다시 실행할 실패 케이스가 없습니다. 실패한 케이스가 모두 삭제되었을 수 있습니다",
    ],
  ])("explains why the run cannot be rerun: %s", async (detail, message) => {
    capture("post", "/api/runs", () => problem(409, detail));

    await expect(rerunFailed(43)).resolves.toEqual({ error: message });
  });

  it("tells when the limit of manual runs is reached", async () => {
    capture("post", "/api/runs", () => problem(429, "4 manual runs are already in progress"));

    await expect(rerunFailed(43)).resolves.toEqual({
      error: "진행 중인 실행이 많아 지금은 시작할 수 없습니다. 실행이 끝난 뒤 다시 시도해 주세요",
    });
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

  it("sends the version the editor was opened with on update, never on create", async () => {
    const updated = capture("patch", "/api/cases/9", () => HttpResponse.json({ id: 9 }));
    const created = capture("post", "/api/cases", () => HttpResponse.json({ id: 12 }, { status: 201 }));

    await captureRedirect(() => updateCase(9, 4, { ...draft, version: 5 }));
    await captureRedirect(() => createCase(4, { ...draft, version: 5 }));

    expect(updated).toEqual([expect.objectContaining({ version: 5 })]);
    expect(created).toEqual([expect.not.objectContaining({ version: expect.anything() })]);
  });

  it("returns the saved version when the case stays on the form so the next save uses it", async () => {
    capture("patch", "/api/cases/9", () => HttpResponse.json({ id: 9, version: 6 }));
    capture("post", "/api/runs", () => problem(404, "environment 3 not found"));

    await expect(updateCase(9, 4, { ...draft, version: 5 }, 3)).resolves.toMatchObject({ version: 6 });
  });

  it.each([-1, 1.5, "5"])("rejects a malformed version %j in the draft", async (version) => {
    await expect(updateCase(9, 4, { ...draft, version: version as number })).resolves.toEqual({
      errors: ["요청 형식이 올바르지 않습니다"],
    });
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

  it("names the rule whose JsonPath Spring could not read", async () => {
    server.use(
      http.post(springUrl("/api/cases"), () =>
        HttpResponse.json(
          {
            status: 400,
            detail: "invalid JSON path: $.[",
            code: "INVALID_JSON_PATH",
            params: { expression: "$.[", list: "assertions", index: "1" },
          },
          { status: 400 },
        ),
      ),
    );

    await expect(createCase(4, draft)).resolves.toEqual({
      errors: ["판정 규칙 2: JsonPath 문법이 올바르지 않습니다: $.["],
    });
  });
});

describe("updateSchedules", () => {
  it("changes the chosen schedules at once and reloads the list", async () => {
    const bodies = capture("patch", "/api/schedules", () => HttpResponse.json([]));

    await expect(updateSchedules([3, 4], { enabled: false })).resolves.toEqual({});
    await expect(updateSchedules([3], { skipHolidays: true })).resolves.toEqual({});

    expect(bodies).toEqual([
      { scheduleIds: [3, 4], enabled: false },
      { scheduleIds: [3], skipHolidays: true },
    ]);
    expect(refresh).toHaveBeenCalledTimes(2);
  });

  it("rejects requests that did not come from the list without calling Spring", async () => {
    const bodies = capture("patch", "/api/schedules", () => HttpResponse.json([]));

    for (const ids of [[], [0], [1.5], Array.from({ length: 201 }, (_, i) => i + 1)]) {
      await expect(updateSchedules(ids, { enabled: true })).resolves.toEqual({
        error: "스케줄을 1~200개 골라 주세요",
      });
    }
    await expect(updateSchedules([1], {})).resolves.toEqual({ error: "요청 형식이 올바르지 않습니다" });
    await expect(updateSchedules([1], { enabled: "yes" } as unknown as { enabled: boolean })).resolves.toEqual({
      error: "요청 형식이 올바르지 않습니다",
    });
    expect(bodies).toEqual([]);
    // 200개까지는 보냄
    await updateSchedules(
      Array.from({ length: 200 }, (_, i) => i + 1),
      { enabled: true },
    );
    expect(bodies).toHaveLength(1);
  });

  it("reloads the list when one of them was deleted meanwhile", async () => {
    capture("patch", "/api/schedules", () => problem(404, "test schedule 4 not found"));

    await expect(updateSchedules([3, 4], { enabled: true })).resolves.toEqual({
      error: "고른 스케줄 중 삭제된 것이 있어 아무것도 바꾸지 않았습니다. 목록을 새로 고쳤으니 다시 골라 주세요",
    });
    expect(refresh).toHaveBeenCalledOnce();
  });
});

describe("schedules", () => {
  it("creates a schedule for the suite and environment", async () => {
    const bodies = capture("post", "/api/schedules", () => HttpResponse.json({ id: 5 }, { status: 201 }));

    await expect(createSchedule(2, undefined, form({ environmentId: "3", cron: "10 9 * * 1-5" }))).resolves.toEqual({});

    expect(bodies).toEqual([{ suiteId: 2, environmentId: 3, cron: "10 9 * * 1-5", skipHolidays: false }]);
    expect(refresh).toHaveBeenCalledOnce();
  });

  it("creates a schedule that skips holidays when checked", async () => {
    const bodies = capture("post", "/api/schedules", () => HttpResponse.json({ id: 5 }, { status: 201 }));

    await createSchedule(2, undefined, form({ environmentId: "3", cron: "10 9 * * 1-5", skipHolidays: "true" }));

    expect(bodies).toEqual([{ suiteId: 2, environmentId: 3, cron: "10 9 * * 1-5", skipHolidays: true }]);
  });

  it("requires an environment", async () => {
    await expect(createSchedule(2, undefined, form({ cron: "10 9 * * 1-5" }))).resolves.toEqual({
      error: "실행할 환경을 선택해 주세요",
    });
  });

  it("shows the Spring message for a bad request without a code", async () => {
    server.use(http.post(springUrl("/api/schedules"), () => problem(400, "cron must not be blank")));

    await expect(createSchedule(2, undefined, form({ environmentId: "3", cron: " " }))).resolves.toEqual({
      error: "입력값을 확인해 주세요: cron must not be blank",
    });
  });

  it("says which cron field is wrong in Korean", async () => {
    server.use(
      http.post(springUrl("/api/schedules"), () =>
        HttpResponse.json(
          {
            status: 400,
            detail: "invalid cron: 61 9 * * * (...)",
            code: "CRON_INVALID_FIELD",
            params: { field: "minute", value: "61" },
          },
          { status: 400 },
        ),
      ),
    );

    await expect(createSchedule(2, undefined, form({ environmentId: "3", cron: "61 9 * * *" }))).resolves.toEqual({
      error: "cron의 분(0~59) 칸이 올바르지 않습니다: 61",
    });
  });

  it("sends cron and environment when editing", async () => {
    const bodies = capture("patch", "/api/schedules/5", () => HttpResponse.json({ id: 5 }));

    await updateSchedule(5, undefined, form({ cron: "30 17 * * 1-5", environmentId: "4", skipHolidays: "true" }));
    // 체크 해제된 상자는 폼에 없음
    await updateSchedule(5, undefined, form({ cron: "30 17 * * 1-5", environmentId: "4" }));

    expect(bodies).toEqual([
      { cron: "30 17 * * 1-5", environmentId: 4, skipHolidays: true },
      { cron: "30 17 * * 1-5", environmentId: 4, skipHolidays: false },
    ]);
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

  it("asks to reload when someone else changed the schedule first", async () => {
    server.use(
      http.patch(springUrl("/api/schedules/5"), () =>
        problem(409, "modified by another request; reload and try again"),
      ),
    );

    await expect(updateSchedule(5, undefined, form({ cron: "30 17 * * 1-5", environmentId: "4" }))).resolves.toEqual({
      error: "다른 곳에서 먼저 수정했습니다. 최신 내용을 확인한 뒤 다시 시도해 주세요",
    });
    expect(refresh).toHaveBeenCalled();
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
    suite: { id: 4, name: "주문" },
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
