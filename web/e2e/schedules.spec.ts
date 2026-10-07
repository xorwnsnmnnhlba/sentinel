import type { APIRequestContext } from "@playwright/test";

import { accessibilityViolations, expect, mainMenu, test } from "./fixtures";

/**
 * 전체 스케줄 화면. 프로젝트로 좁혀 여러 스케줄을 골라 휴일 건너뛰기·켜기·끄기를 한 번에
 */
const api = process.env.E2E_API_URL ?? "http://localhost:18080";
const stamp = Date.now();
const projectName = `schedules-${stamp}`;

let projectId = 0;

async function send(request: APIRequestContext, method: "post" | "patch", path: string, data: object) {
  const session = await request.post(`${api}/api/sessions`, {
    data: { email: "admin@sentinel.test", password: "e2e-admin-password" },
  });
  const headers = { Authorization: `Bearer ${(await session.json()).token}` };
  const response = await request[method](`${api}${path}`, { headers, data });
  expect(response.ok(), `${method} ${path} ${response.status()}`).toBe(true);
  return (await response.json()) as { id: number };
}

test.beforeAll(async ({ request }) => {
  projectId = (await send(request, "post", "/api/projects", { name: projectName })).id;
  const environment = await send(request, "post", "/api/environments", {
    projectId,
    name: "dev",
    baseUrl: "http://e2e-api:8080",
  });
  const smoke = await send(request, "post", "/api/suites", { projectId, name: "smoke" });
  const billing = await send(request, "post", "/api/suites", { projectId, name: "billing" });
  await send(request, "post", "/api/schedules", {
    suiteId: smoke.id,
    environmentId: environment.id,
    cron: "10 9 * * 1-5",
  });
  const off = await send(request, "post", "/api/schedules", {
    suiteId: billing.id,
    environmentId: environment.id,
    cron: "0 18 * * *",
  });
  await send(request, "patch", `/api/schedules/${off.id}`, { enabled: false });
});

test.beforeEach(async ({ page }) => {
  await page.goto("/login");
  await page.getByLabel("이메일").fill("admin@sentinel.test");
  await page.getByLabel("비밀번호").fill("e2e-admin-password");
  await page.getByRole("button", { name: "로그인" }).click();
  await expect(mainMenu(page).getByRole("link", { name: "스케줄" })).toBeVisible();
});

test("프로젝트로 좁혀 여러 스케줄의 휴일 건너뛰기·활성을 한 번에 바꿈", async ({ page }) => {
  await mainMenu(page).getByRole("link", { name: "스케줄" }).click();
  await expect(page.getByRole("heading", { name: "스케줄", level: 1 })).toBeVisible();
  await page.getByLabel("프로젝트").selectOption({ label: projectName });
  await page.getByRole("button", { name: "조회" }).click();
  await expect(page).toHaveURL(new RegExp(`projectId=${projectId}`));

  const table = page.getByRole("table", { name: "스케줄" });
  const smoke = table.getByRole("row", { name: /smoke/ });
  const billing = table.getByRole("row", { name: /billing/ });
  // 켜진 스케줄이 먼저, 꺼진 스케줄은 뒤
  await expect(table.getByRole("row").nth(1)).toContainText("smoke");
  await expect(billing).toContainText("비활성");
  expect(await accessibilityViolations(page)).toEqual([]);

  const toolbar = page.getByRole("toolbar", { name: "고른 스케줄 변경" });
  await page.getByRole("checkbox", { name: "모두 선택" }).check();
  await expect(toolbar).toContainText("2개 선택");
  await toolbar.getByRole("button", { name: "휴일 건너뛰기 켜기" }).click();
  await expect(smoke).toContainText("휴일 제외");
  await expect(billing).toContainText("휴일 제외");
  await expect(toolbar).toContainText("0개 선택");

  await page.getByRole("checkbox", { name: "billing dev 0 18 * * * 선택" }).check();
  await toolbar.getByRole("button", { name: "켜기", exact: true }).click();
  await expect(billing).toContainText("활성");
  await expect(billing).not.toContainText("비활성");

  await page.getByRole("checkbox", { name: "smoke dev 10 9 * * 1-5 선택" }).check();
  await toolbar.getByRole("button", { name: "끄기", exact: true }).click();
  const confirm = page.getByRole("alertdialog", { name: "스케줄 1개 끄기" });
  expect(await accessibilityViolations(page)).toEqual([]);
  await confirm.getByRole("button", { name: "끄기" }).click();
  await expect(confirm).toBeHidden();
  await expect(smoke).toContainText("비활성");

  // 변경 이력은 스케줄마다
  await page.goto("/change-logs?targetType=SCHEDULE");
  await expect(page.getByRole("row", { name: /smoke.*휴일 건너뛰기/ }).first()).toBeVisible();
});
