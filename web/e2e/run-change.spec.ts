import type { APIRequestContext } from "@playwright/test";

import { accessibilityViolations, expect, mainMenu, test } from "./fixtures";

/**
 * 결과 상세의 직전 실행 대비 변화. 첫 실행 뒤 한 케이스의 판정 규칙을 바꿔 새로 실패하게 만든 다음 다시 실행
 * 케이스는 api 자신을 호출 (없는 초대 링크 조회라 인증 없이 404)
 */
const api = process.env.E2E_API_URL ?? "http://localhost:18080";
const stamp = Date.now();
const suiteName = `변화 ${stamp}`;

let suiteId = 0;
let firstRunId = 0;

async function call(
  request: APIRequestContext,
  method: "post" | "patch",
  path: string,
  data: object,
): Promise<{ id: number }> {
  const session = await request.post(`${api}/api/sessions`, {
    data: { email: "admin@sentinel.test", password: "e2e-admin-password" },
  });
  const headers = { Authorization: `Bearer ${(await session.json()).token}` };
  const response = await request[method](`${api}${path}`, { headers, data });
  expect(response.ok(), `${method} ${path} ${response.status()}`).toBe(true);
  return (await response.json()) as { id: number };
}

test.beforeAll(async ({ request }) => {
  const project = await call(request, "post", "/api/projects", { name: `change-${stamp}` });
  const environment = await call(request, "post", "/api/environments", {
    projectId: project.id,
    name: "self",
    baseUrl: "http://e2e-api:8080",
  });
  suiteId = (await call(request, "post", "/api/suites", { projectId: project.id, name: suiteName })).id;
  const caseOf = (name: string) => ({
    suiteId,
    name,
    request: { method: "GET", path: `/api/invites/${name}`, headers: {}, body: null },
    assertions: [{ type: "statusCode", anyOf: ["404"] }],
  });
  await call(request, "post", "/api/cases", caseOf("stable"));
  const breaking = await call(request, "post", "/api/cases", caseOf("breaking"));
  firstRunId = (await call(request, "post", "/api/runs", { suiteId, environmentId: environment.id })).id;
  // 두 번째 실행에서 실패하도록 기대 상태 코드 변경
  await call(request, "patch", `/api/cases/${breaking.id}`, { assertions: [{ type: "statusCode", anyOf: ["200"] }] });
});

test.beforeEach(async ({ page }) => {
  await page.goto("/login");
  await page.getByLabel("이메일").fill("admin@sentinel.test");
  await page.getByLabel("비밀번호").fill("e2e-admin-password");
  await page.getByRole("button", { name: "로그인" }).click();
  await expect(mainMenu(page).getByRole("link", { name: "프로젝트" })).toBeVisible();
});

test("다시 실행하면 직전 실행 대비 새로 실패한 케이스를 표시", async ({ page }) => {
  await page.goto(`/suites/${suiteId}`);
  await expect(page.getByRole("heading", { name: suiteName, level: 1 })).toBeVisible();
  await page.getByRole("button", { name: "지금 실행" }).click();

  await expect(page).toHaveURL(/\/runs\/\d+$/);
  await expect(page.getByRole("heading", { name: new RegExp(suiteName), level: 1 })).toBeVisible();
  await expect(page.getByRole("link", { name: `직전 실행 #${firstRunId}` })).toBeVisible();
  await expect(page.getByText("새로 실패 1")).toBeVisible();
  // 새로 실패한 케이스는 펼친 상태, 그대로 통과한 케이스는 배지 없음
  const breaking = page.getByRole("button", { name: /breaking/ });
  await expect(breaking).toContainText("새로 실패");
  await expect(breaking).toHaveAttribute("aria-expanded", "true");
  await expect(page.getByRole("button", { name: /stable/ })).not.toContainText("새로 실패");
  expect(await accessibilityViolations(page)).toEqual([]);

  await page.getByRole("link", { name: `직전 실행 #${firstRunId}` }).click();
  await expect(page).toHaveURL(new RegExp(`/runs/${firstRunId}$`));
  await expect(page.getByText(/완료된 이전 실행이 없어 비교하지 않습니다/)).toBeVisible();
});
