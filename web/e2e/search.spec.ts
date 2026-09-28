import type { APIRequestContext } from "@playwright/test";

import { accessibilityViolations, expect, mainMenu, test } from "./fixtures";

/** 케이스 검색. 이름·경로 일부로 모든 스위트의 케이스를 찾고 수정 화면으로 이동 */
const api = process.env.E2E_API_URL ?? "http://localhost:18080";
const stamp = Date.now();
const token = `srch${stamp}`;

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
  const project = await send(request, "/api/projects", { name: `search-${stamp}` });
  const suite = await send(request, "/api/suites", { projectId: project.id, name: `검색 스위트 ${stamp}` });
  const caseOf = (name: string, path: string) => ({
    suiteId: suite.id,
    name,
    request: { method: "GET", path, headers: {}, body: null },
  });
  await send(request, "/api/cases", caseOf("결제 승인", `/${token}/v1/charge`));
  await send(request, "/api/cases", caseOf("결제 내역", `/${token}/v1/history`));
  await send(request, "/api/cases", caseOf(`로그인 ${token}`, "/auth/login"));
});

test.beforeEach(async ({ page }) => {
  await page.goto("/login");
  await page.getByLabel("이메일").fill("admin@sentinel.test");
  await page.getByLabel("비밀번호").fill("e2e-admin-password");
  await page.getByRole("button", { name: "로그인" }).click();
  await expect(mainMenu(page).getByRole("link", { name: "프로젝트" })).toBeVisible();
});

test("경로 일부로 찾은 케이스를 강조해 보여 주고 수정 화면으로 이동", async ({ page }) => {
  await mainMenu(page).getByRole("link", { name: "케이스 검색" }).click();
  await expect(page.getByRole("heading", { name: "케이스 검색", level: 1 })).toBeVisible();

  // 대소문자 무시: 경로(두 건)와 이름(한 건) 모두
  await page.getByLabel("검색어").fill(token.toUpperCase());
  await page.getByRole("button", { name: "검색", exact: true }).click();
  await expect(page).toHaveURL(/\/search\?q=/);
  const results = page.getByRole("table", { name: "검색 결과" });
  await expect(results.getByRole("row")).toHaveCount(4);
  await expect(page.getByText("3건", { exact: true })).toBeVisible();
  await expect(results.locator("mark").first()).toHaveText(token);
  expect(await accessibilityViolations(page)).toEqual([]);

  await page.getByLabel("검색어").fill(`${token}/v1/charge`);
  await page.getByRole("button", { name: "검색", exact: true }).click();
  await expect(results.getByRole("row")).toHaveCount(2);
  await results.getByRole("link", { name: "결제 승인" }).click();
  await expect(page.getByRole("heading", { name: "케이스 수정", level: 1 })).toBeVisible();
  await expect(page.getByLabel("경로")).toHaveValue(`/${token}/v1/charge`);
});

test("일치하는 케이스가 없으면 안내", async ({ page }) => {
  await page.goto(`/search?q=${encodeURIComponent(`없는-${token}`)}`);

  await expect(page.getByText(/검색 결과가 없습니다/)).toBeVisible();
});
