import { expect, test } from "@playwright/test";

/**
 * 로그인 없이 접근 차단, 로그인·로그아웃. 스택의 최초 관리자 계정 사용
 */
const email = "admin@sentinel.test";
const password = "e2e-admin-password";

test("로그인하지 않으면 로그인 화면으로 이동", async ({ page }) => {
  await page.goto("/projects");

  await expect(page).toHaveURL(/\/login\?from=%2Fprojects$/);
  await expect(page.getByRole("heading", { name: "로그인" })).toBeVisible();
  // 로그인 전에는 메뉴 미표시
  await expect(page.getByRole("banner").getByRole("link", { name: "실행 이력" })).toHaveCount(0);
});

test("잘못된 비밀번호는 같은 문구로 거부", async ({ page }) => {
  await page.goto("/login");
  await page.getByLabel("이메일").fill(email);
  await page.getByLabel("비밀번호").fill("wrong-password");
  await page.getByRole("button", { name: "로그인" }).click();

  // Next.js 라우트 알림(role=alert)은 제외
  await expect(page.locator('[role="alert"]:not(#__next-route-announcer__)')).toHaveText(
    "이메일 또는 비밀번호가 올바르지 않습니다",
  );
  await expect(page).toHaveURL(/\/login$/);
  // 입력한 이메일은 유지
  await expect(page.getByLabel("이메일")).toHaveValue(email);
});

test("로그인 후 원래 가려던 화면으로 이동하고 로그아웃하면 다시 차단", async ({ page }) => {
  await page.goto("/runs");
  await page.getByLabel("이메일").fill(email);
  await page.getByLabel("비밀번호").fill(password);
  await page.getByRole("button", { name: "로그인" }).click();

  await expect(page).toHaveURL(/\/runs$/);
  await expect(page.getByText(email)).toBeVisible();

  await page.getByRole("button", { name: "로그아웃" }).click();

  await expect(page).toHaveURL(/\/login$/);
  await page.goto("/runs");
  await expect(page).toHaveURL(/\/login\?from=%2Fruns$/);
});
