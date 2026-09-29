import type { APIRequestContext } from "@playwright/test";

import { accessibilityViolations, expect, mainMenu, test, waitForRun } from "./fixtures";

/**
 * 실패 다시 실행. 실패한 케이스만 다시 실행하고, 그 케이스가 쓰는 추출 값을 만드는 앞 케이스를 함께 실행
 * 케이스는 api 자신을 호출 (없는 초대 링크 조회라 인증 없이 404, 본문의 status 값 404를 추출해 뒤 케이스 경로에 사용)
 */
const api = process.env.E2E_API_URL ?? "http://localhost:18080";
const stamp = Date.now();
const suiteName = `다시 실행 ${stamp}`;

let originalRunId = 0;

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
  const project = await create(request, "/api/projects", { name: `rerun-${stamp}` });
  const environment = await create(request, "/api/environments", {
    projectId: project.id,
    name: "self",
    baseUrl: "http://e2e-api:8080",
  });
  const suite = await create(request, "/api/suites", { projectId: project.id, name: suiteName });
  const caseOf = (name: string, path: string, expected: string, extractors: object[] = []) => ({
    suiteId: suite.id,
    name,
    request: { method: "GET", path, headers: {}, body: null },
    assertions: [{ type: "statusCode", anyOf: [expected] }],
    extractors,
  });
  await create(
    request,
    "/api/cases",
    caseOf("source", "/api/invites/source", "404", [{ name: "code", from: "BODY", expression: "$.status" }]),
  );
  await create(request, "/api/cases", caseOf("stable", "/api/invites/stable", "404"));
  // 추출한 값(404)을 경로에 쓰고 200을 기대하므로 실패
  await create(request, "/api/cases", caseOf("broken", "/api/invites/{{code}}", "200"));
  originalRunId = (await create(request, "/api/runs", { suiteId: suite.id, environmentId: environment.id })).id;
  await waitForRun(request, api, await sessionHeaders(request), originalRunId);
});

test.beforeEach(async ({ page }) => {
  await page.goto("/login");
  await page.getByLabel("이메일").fill("admin@sentinel.test");
  await page.getByLabel("비밀번호").fill("e2e-admin-password");
  await page.getByRole("button", { name: "로그인" }).click();
  await expect(mainMenu(page).getByRole("link", { name: "프로젝트" })).toBeVisible();
});

test("실패한 케이스와 그 케이스가 쓰는 값을 만드는 앞 케이스만 다시 실행", async ({ page }) => {
  await page.goto(`/runs/${originalRunId}`);
  await expect(page.getByRole("heading", { name: new RegExp(suiteName), level: 1 })).toBeVisible();

  await page.getByRole("button", { name: "실패만 다시 실행" }).click();

  await expect(page).not.toHaveURL(new RegExp(`/runs/${originalRunId}$`));
  await expect(page).toHaveURL(/\/runs\/\d+$/);
  const heading = page.getByRole("heading", { name: new RegExp(suiteName), level: 1 });
  await expect(heading).toContainText("실패 다시 실행");
  // 끝나면 여전히 실패한 케이스를 다시 실행하는 버튼 표시 (진행 중에는 중지 버튼)
  await expect(page.getByRole("button", { name: "실패만 다시 실행" })).toBeVisible({ timeout: 20_000 });
  // 케이스 행(펼침 버튼)은 실행한 두 케이스만
  await expect(page.getByRole("button", { name: /source|stable|broken/ })).toHaveCount(2);
  await expect(page.getByRole("button", { name: /source/ })).toContainText("함께 실행");
  await expect(page.getByRole("button", { name: /broken/ })).not.toContainText("함께 실행");
  await expect(page.getByRole("button", { name: /stable/ })).toHaveCount(0);
  await expect(page.getByText(/완료된 이전 실행이 없어 비교하지 않습니다/)).toHaveCount(0);
  expect(await accessibilityViolations(page)).toEqual([]);
  const rerunUrl = page.url();

  // 실행 이력에는 원래 실행과 함께 표시
  await page.goto("/runs");
  await expect(page.getByRole("row", { name: new RegExp(`#${originalRunId} 실패 다시 실행`) })).toBeVisible();
  await page.goto(rerunUrl);
  await expect(heading).toContainText("실패 다시 실행");

  await page.getByRole("link", { name: `#${originalRunId}`, exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`/runs/${originalRunId}$`));
  await expect(page.getByRole("heading", { name: new RegExp(suiteName), level: 1 })).not.toContainText(
    "실패 다시 실행",
  );
});
