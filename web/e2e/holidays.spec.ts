import type { APIRequestContext } from "@playwright/test";

import { accessibilityViolations, chooseAction, expect, mainMenu, test } from "./fixtures";

/**
 * 휴일. 관리자가 휴일 화면에서 등록·삭제하고 변경 이력에 표시, 스케줄 대화상자의 휴일 건너뛰기
 * 휴일은 전체 공용이고 날짜가 유일하므로, 다시 실행해도 겹치지 않게 먼 해의 날짜를 실행마다 다르게 고름
 */
const api = process.env.E2E_API_URL ?? "http://localhost:18080";
const stamp = Date.now();
const year = 2090 + (stamp % 10);
const date = `${year}-${String((stamp % 12) + 1).padStart(2, "0")}-${String((stamp % 28) + 1).padStart(2, "0")}`;
const name = `창립기념일-${stamp}`;

let suiteId = 0;

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
  const project = await create(request, "/api/projects", { name: `holidays-${stamp}` });
  await create(request, "/api/environments", { projectId: project.id, name: "dev", baseUrl: "http://e2e-api:8080" });
  suiteId = (await create(request, "/api/suites", { projectId: project.id, name: "holiday-suite" })).id;
});

test.beforeEach(async ({ page }) => {
  await page.goto("/login");
  await page.getByLabel("이메일").fill("admin@sentinel.test");
  await page.getByLabel("비밀번호").fill("e2e-admin-password");
  await page.getByRole("button", { name: "로그인" }).click();
  await expect(mainMenu(page).getByRole("link", { name: "휴일" })).toBeVisible();
});

test("휴일을 등록·삭제하고 변경 이력에 표시", async ({ page }) => {
  await mainMenu(page).getByRole("link", { name: "휴일" }).click();
  await expect(page.getByRole("heading", { name: "휴일", level: 1 })).toBeVisible();
  expect(await accessibilityViolations(page)).toEqual([]);

  await page.getByRole("button", { name: "휴일 추가" }).click();
  const dialog = page.getByRole("dialog", { name: "휴일 추가" });
  await dialog.getByLabel("날짜 (KST)").fill(date);
  await dialog.getByLabel("이름").fill(name);
  expect(await accessibilityViolations(page)).toEqual([]);
  await dialog.getByRole("button", { name: "등록" }).click();
  await expect(dialog).toBeHidden();

  // 다른 해의 휴일은 연도를 옮겨 확인
  await page.goto(`/holidays?year=${year - 1}`);
  await page.getByRole("link", { name: `${year}년` }).click();
  await expect(page).toHaveURL(new RegExp(`year=${year}$`));
  const row = page.getByRole("table", { name: `${year}년 휴일` }).getByRole("row", { name: new RegExp(name) });
  await expect(row).toContainText(date);

  // 같은 날짜는 다시 등록할 수 없음
  await page.getByRole("button", { name: "휴일 추가" }).click();
  await dialog.getByLabel("날짜 (KST)").fill(date);
  await dialog.getByLabel("이름").fill("중복");
  await dialog.getByRole("button", { name: "등록" }).click();
  await expect(dialog.getByRole("alert")).toHaveText(`${date}은(는) 이미 휴일로 등록되어 있습니다`);
  await page.keyboard.press("Escape");
  await expect(dialog).toBeHidden();

  await chooseAction(page, `휴일 ${date} ${name} 작업`, "삭제");
  await page.getByRole("alertdialog").getByRole("button", { name: "삭제" }).click();
  await expect(page.getByRole("alertdialog")).toBeHidden();
  await expect(page.getByRole("row", { name: new RegExp(name) })).toHaveCount(0);

  // 변경 이력: 등록·삭제를 "휴일"로, 이름은 "날짜 이름"
  await page.goto("/change-logs?targetType=HOLIDAY");
  await expect(page.getByRole("heading", { name: "변경 이력", level: 1 })).toBeVisible();
  await expect(page.getByRole("row", { name: new RegExp(`휴일.*${date} ${name}`) })).toHaveCount(2);
});

test("스케줄 대화상자에서 휴일 건너뛰기를 켜고 끔", async ({ page }) => {
  await page.goto(`/suites/${suiteId}`);
  await page.getByRole("button", { name: "스케줄 추가" }).click();
  const add = page.getByRole("dialog", { name: "스케줄 추가" });
  await add.getByRole("checkbox", { name: "휴일 건너뛰기" }).check();
  await add.getByRole("button", { name: "등록" }).click();
  await expect(add).toBeHidden();

  const row = page.getByRole("row", { name: /10 9 \* \* 1-5/ });
  await expect(row).toContainText("휴일 제외");

  await chooseAction(page, "스케줄 10 9 * * 1-5 작업", "수정");
  const edit = page.getByRole("dialog", { name: "스케줄 수정" });
  await expect(edit.getByRole("checkbox", { name: "휴일 건너뛰기" })).toBeChecked();
  await edit.getByRole("checkbox", { name: "휴일 건너뛰기" }).uncheck();
  await edit.getByRole("button", { name: "저장" }).click();
  await expect(edit).toBeHidden();
  await expect(row).not.toContainText("휴일 제외");
});
