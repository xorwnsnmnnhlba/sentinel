import type { APIRequestContext } from "@playwright/test";

import { chooseAction, expect, mainMenu, test } from "./fixtures";

/**
 * 변경 이력. 화면에서 바꾼 설정이 누가·무엇을 바꿨는지로 남고, 항목 메뉴에서 그 항목의 이력만 볼 수 있음
 */
const api = process.env.E2E_API_URL ?? "http://localhost:18080";
const stamp = Date.now();
const projectName = `이력-${stamp}`;

let projectId = 0;
let caseId = 0;

async function send(request: APIRequestContext, path: string, data: object): Promise<{ id: number }> {
  const session = await request.post(`${api}/api/sessions`, {
    data: { email: "admin@sentinel.test", password: "e2e-admin-password" },
  });
  const headers = { Authorization: `Bearer ${(await session.json()).token}` };
  const response = await request.post(`${api}${path}`, { headers, data });
  expect(response.ok(), `POST ${path} ${response.status()}`).toBe(true);
  return (await response.json()) as { id: number };
}

test.beforeAll(async ({ request }) => {
  projectId = (await send(request, "/api/projects", { name: projectName })).id;
  await send(request, "/api/environments", {
    projectId,
    name: "dev",
    baseUrl: "http://e2e-api:8080",
    variables: { userId: "user-value-before" },
  });
  const suite = await send(request, "/api/suites", { projectId, name: "smoke" });
  caseId = (
    await send(request, "/api/cases", {
      suiteId: suite.id,
      name: "health",
      request: { method: "GET", path: "/api/invites/unknown", headers: {}, body: null },
      assertions: [],
    })
  ).id;
});

test.beforeEach(async ({ page }) => {
  await page.goto("/login");
  await page.getByLabel("이메일").fill("admin@sentinel.test");
  await page.getByLabel("비밀번호").fill("e2e-admin-password");
  await page.getByRole("button", { name: "로그인" }).click();
  await expect(mainMenu(page).getByRole("link", { name: "프로젝트" })).toBeVisible();
});

test("케이스를 고치면 그 케이스의 이력에 누가 무엇을 바꿨는지 남음", async ({ page }) => {
  await page.goto(`/cases/${caseId}`);
  await expect(page.getByRole("heading", { name: "케이스 수정", level: 1 })).toBeVisible();
  await page.getByLabel("이름", { exact: true }).fill("health check");
  await page.getByRole("button", { name: "저장", exact: true }).click();
  await expect(page.getByRole("heading", { name: "smoke", level: 1 })).toBeVisible();

  await chooseAction(page, "케이스 health check 작업", "변경 이력");
  await expect(page.getByRole("heading", { name: "변경 이력", level: 1 })).toBeVisible();
  await expect(page.getByText(/health check.*의 변경 이력만 표시 중/)).toBeVisible();
  const rows = page.getByRole("table", { name: "변경 이력" }).getByRole("row");
  await expect(rows).toHaveCount(3);
  await expect(rows.nth(1)).toContainText("admin@sentinel.test");
  await expect(rows.nth(1)).toContainText("수정");
  await expect(rows.nth(1)).toContainText("이름");
  await expect(rows.nth(2)).toContainText("등록");

  await page.getByRole("link", { name: "전체 보기" }).click();
  await expect(page.getByRole("form", { name: "변경 이력 필터" })).toBeVisible();
});

test("환경 변수를 바꾸면 값 없이 변수 이름만 남고 프로젝트로 좁혀 볼 수 있음", async ({ page }) => {
  await page.goto(`/projects/${projectId}`);
  await expect(page.getByRole("heading", { name: projectName, level: 1 })).toBeVisible();
  await chooseAction(page, "환경 dev 작업", "수정");
  const dialog = page.getByRole("dialog");
  await dialog.getByLabel("변수 1 값").fill("user-value-after");
  await dialog.getByRole("button", { name: "저장" }).click();
  await expect(dialog).toBeHidden();

  await mainMenu(page).getByRole("link", { name: "변경 이력" }).click();
  await expect(page.getByRole("heading", { name: "변경 이력", level: 1 })).toBeVisible();
  await page.getByLabel("프로젝트").selectOption({ label: projectName });
  await page.getByRole("button", { name: "조회" }).click();
  await expect(page).toHaveURL(new RegExp(`projectId=${projectId}`));

  const rows = page.getByRole("table", { name: "변경 이력" }).getByRole("row");
  await expect(rows.nth(1)).toContainText("환경");
  await expect(rows.nth(1)).toContainText("변수 userId");
  // 프로젝트·환경·스위트·케이스 등록 4건, 앞 테스트의 케이스 이름 수정 1건, 환경 변수 수정 1건
  await expect(page.getByText(/조건에 맞는 기록 6건/)).toBeVisible();
  expect(await page.content()).not.toContain("user-value-after");
  expect(await page.content()).not.toContain("user-value-before");
});
