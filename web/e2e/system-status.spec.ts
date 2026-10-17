import type { Page } from "@playwright/test";

import { accessibilityViolations, expect, sidebar, test } from "./fixtures";

/** 제목으로 패널 찾기 */
function panel(page: Page, title: string) {
  return page.locator("section").filter({ has: page.getByRole("heading", { name: title, exact: true }) });
}

/**
 * 시스템 상태 (관리자). 테스트 스택은 암호화 키만 있고 알림·생존 신호 주소는 없음 (docker-compose.e2e.yml)
 */
test.beforeEach(async ({ page }) => {
  await page.goto("/login");
  await page.getByLabel("이메일").fill("admin@sentinel.test");
  await page.getByLabel("비밀번호").fill("e2e-admin-password");
  await page.getByRole("button", { name: "로그인" }).click();
  await expect(sidebar(page).getByRole("link", { name: "시스템 상태" })).toBeVisible();
});

test("사이드바에서 버전·암호화 키·알림 설정·보관 기간 확인", async ({ page }) => {
  await sidebar(page).getByRole("link", { name: "시스템 상태" }).click();
  await expect(page.getByRole("heading", { name: "시스템 상태", level: 1 })).toBeVisible();

  const version = panel(page, "버전");
  // 릴리스 버전 (MAJOR.MINOR.PATCH, docs/branch-convention.md "6. 릴리스")
  await expect(version.getByText(/^\d+\.\d+\.\d+$/)).toBeVisible();
  await expect(version.getByText(/^V\d+$/)).toBeVisible();

  const key = panel(page, "암호화 키");
  await expect(key).toContainText("사용 중");
  await expect(key).toContainText("풀 수 없는 값 없음");

  const settings = page.getByRole("table", { name: "알림·감시 설정" });
  for (const variable of ["SENTINEL_WEBHOOK_URL", "SENTINEL_WEB_URL", "SENTINEL_HEARTBEAT_URL"]) {
    await expect(settings.getByRole("row").filter({ hasText: variable })).toContainText("없음");
  }
  await expect(settings).toContainText("서버 로그에만 남음");

  await expect(panel(page, "보관 기간")).toContainText("30일");
  expect(await accessibilityViolations(page)).toEqual([]);
});
