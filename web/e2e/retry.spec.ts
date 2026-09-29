import type { APIRequestContext } from "@playwright/test";

import { accessibilityViolations, expect, mainMenu, test, waitForRun } from "./fixtures";

/**
 * 전송 오류 재시도. 닫힌 포트로 보내 매번 연결 실패 (서버에 닿지 않으므로 다시 보냄, 기본 1회) → 재시도 뒤에도 오류
 */
const api = process.env.E2E_API_URL ?? "http://localhost:18080";
const stamp = Date.now();
const suiteName = `재시도 ${stamp}`;

let runId = 0;

async function sessionHeaders(request: APIRequestContext): Promise<Record<string, string>> {
  const session = await request.post(`${api}/api/sessions`, {
    data: { email: "admin@sentinel.test", password: "e2e-admin-password" },
  });
  return { Authorization: `Bearer ${(await session.json()).token}` };
}

async function create(request: APIRequestContext, path: string, data: object): Promise<{ id: number }> {
  const response = await request.post(`${api}${path}`, { headers: await sessionHeaders(request), data });
  expect(response.ok(), `POST ${path} ${response.status()}`).toBe(true);
  return (await response.json()) as { id: number };
}

test.beforeAll(async ({ request }) => {
  const project = await create(request, "/api/projects", { name: `retry-${stamp}` });
  const environment = await create(request, "/api/environments", {
    projectId: project.id,
    name: "closed-port",
    baseUrl: "http://e2e-api:9",
  });
  const suite = await create(request, "/api/suites", { projectId: project.id, name: suiteName });
  await create(request, "/api/cases", {
    suiteId: suite.id,
    name: "health",
    request: { method: "GET", path: "/health", headers: {}, body: null },
  });
  runId = (await create(request, "/api/runs", { suiteId: suite.id, environmentId: environment.id })).id;
  await waitForRun(request, api, await sessionHeaders(request), runId);
});

test.beforeEach(async ({ page }) => {
  await page.goto("/login");
  await page.getByLabel("이메일").fill("admin@sentinel.test");
  await page.getByLabel("비밀번호").fill("e2e-admin-password");
  await page.getByRole("button", { name: "로그인" }).click();
  await expect(mainMenu(page).getByRole("link", { name: "프로젝트" })).toBeVisible();
});

test("응답을 받지 못해 다시 보낸 케이스는 횟수를 표시", async ({ page }) => {
  await page.goto(`/runs/${runId}`);
  await expect(page.getByRole("heading", { name: new RegExp(suiteName), level: 1 })).toBeVisible();

  // 오류 케이스는 펼친 상태
  const row = page.getByRole("button", { name: /health/ });
  await expect(row).toContainText("재시도 1회");
  await expect(row).toHaveAttribute("aria-expanded", "true");
  await expect(page.getByText("CONNECTION_FAILED", { exact: true })).toBeVisible();
  await expect(page.getByText("1회 다시 보냈지만 응답을 받지 못했습니다")).toBeVisible();
  expect(await accessibilityViolations(page)).toEqual([]);
});
