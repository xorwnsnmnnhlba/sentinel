import type { APIRequestContext } from "@playwright/test";

import { accessibilityViolations, chooseAction, expect, mainMenu, test } from "./fixtures";

/**
 * 점검 시간. 프로젝트 화면의 환경 메뉴에서 등록하고 수정·종료, 대시보드의 점검 중 안내, 변경 이력
 * 예정된 점검은 API로 등록 (시작 시각 입력 없이 확인)
 */
const api = process.env.E2E_API_URL ?? "http://localhost:18080";
const stamp = Date.now();
const projectName = `maintenance-${stamp}`;
const dev = `점검-${stamp}`;
const staging = `예정-${stamp}`;

let projectId = 0;

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
  projectId = (await create(request, "/api/projects", { name: projectName })).id;
  await create(request, "/api/environments", { projectId, name: dev, baseUrl: "http://e2e-api:8080" });
  const planned = await create(request, "/api/environments", {
    projectId,
    name: staging,
    baseUrl: "http://e2e-api:8080",
  });
  const tomorrow = Date.now() + 24 * 60 * 60 * 1000;
  await create(request, "/api/maintenance-windows", {
    environmentId: planned.id,
    startsAt: new Date(tomorrow).toISOString(),
    endsAt: new Date(tomorrow + 60 * 60 * 1000).toISOString(),
  });
});

test.beforeEach(async ({ page }) => {
  await page.goto("/login");
  await page.getByLabel("이메일").fill("admin@sentinel.test");
  await page.getByLabel("비밀번호").fill("e2e-admin-password");
  await page.getByRole("button", { name: "로그인" }).click();
  await expect(mainMenu(page).getByRole("link", { name: "프로젝트" })).toBeVisible();
});

test("환경 메뉴에서 점검 시간을 등록하고 연장·종료, 대시보드와 변경 이력에 표시", async ({ page }) => {
  await page.goto(`/projects/${projectId}`);
  await expect(page.getByRole("heading", { name: projectName, level: 1 })).toBeVisible();
  // 예정된 점검은 배지와 "취소" 메뉴
  await expect(page.getByRole("row", { name: new RegExp(staging) }).first()).toContainText("점검 예정");

  // 기본값은 지금부터 1시간
  await chooseAction(page, `환경 ${dev} 작업`, "점검 시간 등록");
  const register = page.getByRole("dialog", { name: `${dev} 점검 시간 등록` });
  await register.getByLabel("사유").fill("GW 배포");
  await register.getByRole("button", { name: "등록" }).click();
  await expect(register).toBeHidden();

  const windows = page.getByRole("table", { name: "점검 시간" });
  const row = windows.getByRole("row", { name: new RegExp(dev) });
  await expect(row).toContainText("점검 중 ~");
  await expect(row).toContainText("GW 배포");
  await expect(
    page
      .getByRole("table")
      .first()
      .getByRole("row", { name: new RegExp(dev) }),
  ).toContainText("점검 중");
  expect(await accessibilityViolations(page)).toEqual([]);

  await chooseAction(page, `${dev} 점검 시간 작업`, "수정");
  const edit = page.getByRole("dialog", { name: `${dev} 점검 시간 수정` });
  await expect(edit.getByLabel("사유")).toHaveValue("GW 배포");
  await edit.getByLabel("사유").fill("GW 배포 연장");
  await edit.getByRole("button", { name: "저장" }).click();
  await expect(edit).toBeHidden();
  await expect(row).toContainText("GW 배포 연장");

  // 대시보드에는 점검 중인 환경만 (예정 제외)
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "대시보드", level: 1 })).toBeVisible();
  await expect(page.getByRole("heading", { name: /점검 중인 환경/ })).toBeVisible();
  await expect(page.getByRole("link", { name: dev, exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: staging, exact: true })).toHaveCount(0);

  await page.getByRole("link", { name: dev, exact: true }).click();
  await expect(page.getByRole("heading", { name: projectName, level: 1 })).toBeVisible();
  await chooseAction(page, `${dev} 점검 시간 작업`, "지금 종료");
  await page.getByRole("alertdialog").getByRole("button", { name: "지금 종료" }).click();
  // 확인 창이 열려 있는 동안은 나머지 화면이 접근성 트리에서 숨겨져 "없음" 확인이 바로 통과하므로, 처리가 끝나 창이 닫힐 때까지 대기
  await expect(page.getByRole("alertdialog")).toBeHidden();
  await expect(windows.getByRole("row", { name: new RegExp(dev) })).toHaveCount(0);

  // 변경 이력: 등록·수정·삭제를 "점검 시간"으로
  await page.goto(`/change-logs?projectId=${projectId}`);
  await expect(page.getByRole("heading", { name: "변경 이력", level: 1 })).toBeVisible();
  await expect(page.getByRole("row", { name: /점검 시간/ })).toHaveCount(4);
  await expect(page.getByRole("row", { name: /점검 시간.*사유/ })).toHaveCount(1);
});
