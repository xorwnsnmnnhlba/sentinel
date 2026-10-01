import type { APIRequestContext } from "@playwright/test";

import { accessibilityViolations, chooseAction, expect, mainMenu, test } from "./fixtures";

/**
 * CI용 API 토큰. 화면에서 발급 → HTTPS 앞단(Caddy)을 거쳐 토큰으로 실행 시작·결과 조회 → 폐기 후 401
 * 토큰 요청은 Caddy가 Spring으로 바로 전달 (그 외는 web)
 */
const api = process.env.E2E_API_URL ?? "http://localhost:18080";
const web = process.env.E2E_WEB_URL ?? "https://localhost:13443";
const stamp = Date.now();
const tokenName = `ci-${stamp}`;

let suiteId = 0;
let environmentId = 0;

async function create(request: APIRequestContext, path: string, data: object): Promise<{ id: number }> {
  const session = await request.post(`${api}/api/sessions`, {
    data: { email: "admin@sentinel.test", password: "e2e-admin-password" },
  });
  const headers = { Authorization: `Bearer ${(await session.json()).token}` };
  const response = await request.post(`${api}${path}`, { headers, data });
  expect(response.ok(), `POST ${path} ${response.status()}`).toBe(true);
  return (await response.json()) as { id: number };
}

test.beforeAll(async ({ request }) => {
  const project = await create(request, "/api/projects", { name: `tokens-${stamp}` });
  environmentId = (
    await create(request, "/api/environments", { projectId: project.id, name: "self", baseUrl: "http://e2e-api:8080" })
  ).id;
  suiteId = (await create(request, "/api/suites", { projectId: project.id, name: `CI 확인 ${stamp}` })).id;
  await create(request, "/api/cases", {
    suiteId,
    name: "없는 초대",
    request: { method: "GET", path: "/api/invites/ci-check", headers: {}, body: null },
    assertions: [{ type: "statusCode", anyOf: ["404"] }],
  });
});

test.beforeEach(async ({ page }) => {
  await page.goto("/login");
  await page.getByLabel("이메일").fill("admin@sentinel.test");
  await page.getByLabel("비밀번호").fill("e2e-admin-password");
  await page.getByRole("button", { name: "로그인" }).click();
  await expect(mainMenu(page).getByRole("link", { name: "프로젝트" })).toBeVisible();
});

test("화면에서 발급한 토큰으로 HTTPS 앞단을 거쳐 실행하고, 폐기하면 거부", async ({ page, request }) => {
  await page.getByRole("link", { name: "API 토큰" }).click();
  await expect(page.getByRole("heading", { name: "API 토큰", level: 1 })).toBeVisible();

  await page.getByRole("button", { name: "토큰 발급" }).click();
  const dialog = page.getByRole("dialog", { name: "API 토큰 발급" });
  await dialog.getByLabel("이름").fill(tokenName);
  await dialog.getByRole("button", { name: "발급" }).click();
  const token = await dialog.getByLabel("발급된 토큰").inputValue();
  expect(token).toMatch(/^snt_/);
  await page.keyboard.press("Escape");
  const row = page.getByRole("table", { name: "내 토큰" }).getByRole("row", { name: new RegExp(tokenName) });
  await expect(row).toContainText(`${token.slice(0, 8)}…`);
  await expect(row).toContainText("사용 전");
  expect(await accessibilityViolations(page)).toEqual([]);

  // CI처럼 HTTPS 앞단으로 실행 시작 (web은 쓰기를 받지 않으므로 202면 Spring으로 전달된 것)
  const headers = { Authorization: `Bearer ${token}` };
  const started = await request.post(`${web}/api/runs`, { headers, data: { suiteId, environmentId } });
  expect(started.status()).toBe(202);
  const runId = ((await started.json()) as { id: number }).id;
  await expect
    .poll(
      async () =>
        ((await (await request.get(`${web}/api/runs/${runId}`, { headers })).json()) as { status: string }).status,
    )
    .toBe("COMPLETED");
  // 실행 이력 외 경로는 토큰으로 조회할 수 없음 (web으로 전달되어 로그인 필요)
  expect((await request.get(`${web}/api/projects`, { headers })).status()).toBe(401);

  // 마지막 사용 시각이 기록되고, 폐기하면 바로 거부
  await page.reload();
  await expect(page.getByRole("heading", { name: "API 토큰", level: 1 })).toBeVisible();
  await expect(row).not.toContainText("사용 전");
  await chooseAction(page, `토큰 ${tokenName} 작업`, "폐기");
  await page.getByRole("alertdialog").getByRole("button", { name: "폐기" }).click();
  // 확인 창이 열려 있는 동안은 나머지 화면이 접근성 트리에서 숨겨져 "없음" 확인이 바로 통과하므로, 처리가 끝나 창이 닫힐 때까지 대기
  await expect(page.getByRole("alertdialog")).toBeHidden();
  await expect(row).toHaveCount(0);
  expect((await request.get(`${web}/api/runs/${runId}`, { headers })).status()).toBe(401);
});
