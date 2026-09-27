import type { APIRequestContext } from "@playwright/test";

import { accessibilityViolations, expect, mainMenu, test } from "./fixtures";

/**
 * 케이스 실행 순서 변경. 순서 변경 창에서 옮기고 저장하면 목록과 스위트 실행 결과가 같은 순서
 * 케이스는 api 자신을 호출 (컨테이너 안에서 http://e2e-api:8080). 없는 초대 링크 조회라 인증 없이 404
 */
const api = process.env.E2E_API_URL ?? "http://localhost:18080";
const stamp = Date.now();
const suiteName = `순서 ${stamp}`;

let suiteId = 0;

async function send(request: APIRequestContext, path: string, data: object): Promise<{ id: number }> {
  const session = await request.post(`${api}/api/sessions`, {
    data: { email: "admin@sentinel.test", password: "e2e-admin-password" },
  });
  const headers = { Authorization: `Bearer ${(await session.json()).token}` };
  const response = await request.post(`${api}${path}`, { headers, data });
  expect(response.ok(), `POST ${path} ${response.status()}`).toBe(true);
  return (await response.json()) as { id: number };
}

function caseOf(name: string) {
  return {
    suiteId,
    name,
    request: { method: "GET", path: `/api/invites/${name}`, headers: {}, body: null },
    assertions: [{ type: "statusCode", anyOf: ["404"] }],
  };
}

test.beforeAll(async ({ request }) => {
  const project = await send(request, "/api/projects", { name: `order-${stamp}` });
  await send(request, "/api/environments", { projectId: project.id, name: "self", baseUrl: "http://e2e-api:8080" });
  suiteId = (await send(request, "/api/suites", { projectId: project.id, name: suiteName })).id;
  for (const name of ["first", "second", "third"]) {
    await send(request, "/api/cases", caseOf(name));
  }
});

test.beforeEach(async ({ page }) => {
  await page.goto("/login");
  await page.getByLabel("이메일").fill("admin@sentinel.test");
  await page.getByLabel("비밀번호").fill("e2e-admin-password");
  await page.getByRole("button", { name: "로그인" }).click();
  await expect(mainMenu(page).getByRole("link", { name: "프로젝트" })).toBeVisible();
});

test.describe.configure({ mode: "serial" });

test("순서를 바꾸면 목록과 스위트 실행이 그 순서", async ({ page }) => {
  await page.goto(`/suites/${suiteId}`);
  await expect(page.getByRole("heading", { name: suiteName, level: 1 })).toBeVisible();

  await page.getByRole("button", { name: "순서 변경" }).click();
  const dialog = page.getByRole("dialog", { name: "케이스 실행 순서" });
  await dialog.getByRole("button", { name: "third 위로 이동" }).click();
  // 옮긴 케이스의 버튼에 초점이 남아 키보드로 이어서 이동
  await expect(dialog.getByRole("button", { name: "third 위로 이동" })).toBeFocused();
  await page.keyboard.press("Enter");
  expect(await accessibilityViolations(page)).toEqual([]);
  await dialog.getByRole("button", { name: "저장" }).click();
  await expect(dialog).toBeHidden();

  const rows = page.getByRole("table", { name: "케이스 목록" }).getByRole("row");
  await expect(rows.nth(1)).toContainText("third");
  await expect(rows.nth(2)).toContainText("first");
  await expect(rows.nth(3)).toContainText("second");

  await page.getByRole("button", { name: "지금 실행" }).click();
  await expect(page).toHaveURL(/\/runs\/\d+$/);
  await expect(page.getByRole("heading", { name: new RegExp(suiteName), level: 1 })).toBeVisible();
  const results = page.getByRole("heading", { level: 3 });
  await expect(results).toHaveText([/third/, /first/, /second/]);
});

test("창을 연 뒤 케이스가 추가되면 저장하지 않고 안내", async ({ page, request }) => {
  await page.goto(`/suites/${suiteId}`);
  await expect(page.getByRole("heading", { name: suiteName, level: 1 })).toBeVisible();
  await page.getByRole("button", { name: "순서 변경" }).click();
  const dialog = page.getByRole("dialog", { name: "케이스 실행 순서" });
  await expect(dialog.getByRole("listitem")).toHaveCount(3);

  await send(request, "/api/cases", caseOf("fourth"));
  await dialog.getByRole("button", { name: "first 아래로 이동" }).click();
  await dialog.getByRole("button", { name: "저장" }).click();

  await expect(dialog.getByRole("alert")).toContainText("그사이 케이스가 추가되거나 삭제되었습니다");
  // 다시 열면 추가된 케이스까지 표시
  await dialog.getByRole("button", { name: "취소" }).click();
  await page.getByRole("button", { name: "순서 변경" }).click();
  await expect(dialog.getByRole("listitem")).toHaveCount(4);
  await expect(dialog.getByRole("listitem").last()).toContainText("fourth");
});
