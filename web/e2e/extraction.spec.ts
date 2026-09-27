import type { APIRequestContext } from "@playwright/test";

import { accessibilityViolations, expect, mainMenu, test } from "./fixtures";

/**
 * 응답 값 추출. 로그인 케이스가 응답의 세션 토큰을 꺼내고, 뒤 케이스가 그 토큰으로 인증하여 조회
 * 케이스는 api 자신을 호출 (컨테이너 안에서 http://e2e-api:8080). 비밀번호는 저장소에 있는 E2E 테스트 계정 값
 */
const api = process.env.E2E_API_URL ?? "http://localhost:18080";
const stamp = Date.now();
const suiteName = `추출 ${stamp}`;

let suiteId = 0;

/** API 등록. 요청 컨텍스트는 beforeAll과 테스트가 각자의 것을 사용 (Playwright 제약) */
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
  const project = await send(request, "/api/projects", { name: `extract-${stamp}` });
  await send(request, "/api/environments", {
    projectId: project.id,
    name: "self",
    baseUrl: "http://e2e-api:8080",
    variables: { password: "e2e-admin-password" },
  });
  suiteId = (await send(request, "/api/suites", { projectId: project.id, name: suiteName })).id;
});

test.beforeEach(async ({ page }) => {
  await page.goto("/login");
  await page.getByLabel("이메일").fill("admin@sentinel.test");
  await page.getByLabel("비밀번호").fill("e2e-admin-password");
  await page.getByRole("button", { name: "로그인" }).click();
  await expect(mainMenu(page).getByRole("link", { name: "프로젝트" })).toBeVisible();
});

test("로그인 응답의 토큰을 꺼내 뒤 케이스의 인증에 사용하고 이력에서 가림", async ({ page, request }) => {
  await page.goto(`/suites/${suiteId}`);
  await expect(page.getByRole("heading", { name: suiteName, level: 1 })).toBeVisible();
  await page.getByRole("link", { name: "케이스 추가" }).click();
  await expect(page.getByRole("heading", { name: "케이스 추가", level: 1 })).toBeVisible();

  await page.getByLabel("이름", { exact: true }).fill("로그인");
  await page.getByLabel("메서드").selectOption("POST");
  await page.getByLabel("경로").fill("/api/sessions");
  await page.getByRole("button", { name: "헤더 추가" }).click();
  await page.getByLabel("헤더 1 이름").fill("Content-Type");
  await page.getByLabel("헤더 1 값").fill("application/json");
  await page.getByLabel(/본문/).fill('{"email": "admin@sentinel.test", "password": "{{password}}"}');
  await page.getByRole("button", { name: "추출 추가" }).click();
  await page.getByLabel("값 추출 1 변수 이름").fill("token");
  await page.getByLabel("값 추출 1 JsonPath").fill("$.token");
  await page.getByRole("switch", { name: "값 추출 1 값 가리기" }).click();
  expect(await accessibilityViolations(page)).toEqual([]);
  await page.getByRole("button", { name: "등록", exact: true }).click();

  await expect(page.getByRole("heading", { name: suiteName, level: 1 })).toBeVisible();
  const cases = page.getByRole("table", { name: "케이스 목록" });
  await expect(cases.getByRole("row", { name: /로그인/ })).toContainText("추출 {{token}}");

  // 로그인 뒤에 실행되는 케이스 (등록 순서 = 실행 순서)
  await send(request, "/api/cases", {
    suiteId,
    name: "내 프로젝트",
    request: { method: "GET", path: "/api/projects", headers: { Authorization: ["Bearer {{token}}"] }, body: null },
    assertions: [{ type: "statusCode", anyOf: ["200"] }],
  });
  await page.reload();
  await expect(cases.getByRole("row", { name: /내 프로젝트/ })).toBeVisible();

  await page.getByRole("button", { name: "지금 실행" }).click();
  await expect(page).toHaveURL(/\/runs\/\d+$/);
  await expect(page.getByRole("heading", { name: new RegExp(suiteName), level: 1 })).toBeVisible();
  // 토큰으로 인증한 조회까지 통과
  await expect(page.getByText("통과 2")).toBeVisible();

  await page.getByRole("button", { name: /로그인/ }).click();
  const extractions = page.getByRole("table", { name: "값 추출" });
  await expect(extractions.getByRole("row", { name: /\{\{token\}\}/ })).toContainText("가림");
  await expect(page.getByText(/"token":\s*"\*\*\*\*"/)).toBeVisible();
  expect(await accessibilityViolations(page)).toEqual([]);
});
