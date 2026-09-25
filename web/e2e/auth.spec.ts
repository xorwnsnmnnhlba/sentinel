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
  // 실행 이력에도 같은 이메일이 나올 수 있으므로 머리말로 한정
  await expect(page.getByRole("banner").getByText(email)).toBeVisible();

  await page.getByRole("button", { name: "로그아웃" }).click();

  await expect(page).toHaveURL(/\/login$/);
  await page.goto("/runs");
  await expect(page).toHaveURL(/\/login\?from=%2Fruns$/);
});

test("로그인 후 이동 주소로 다른 사이트는 받지 않음", async ({ page }) => {
  const external: string[] = [];
  page.on("request", (request) => {
    if (new URL(request.url()).hostname === "evil.example") {
      external.push(request.url());
    }
  });
  // 브라우저는 \\를 /로 바꿔 //evil.example로 해석. 예전 검사(/로 시작, //가 아님)는 통과시켰음
  await page.goto(`/login?from=${encodeURIComponent("/\\evil.example/steal")}`);
  await page.getByLabel("이메일").fill(email);
  await page.getByLabel("비밀번호").fill(password);
  await page.getByRole("button", { name: "로그인" }).click();

  await expect(page.getByRole("heading", { name: "대시보드", level: 1 })).toBeVisible();
  expect(new URL(page.url()).pathname).toBe("/");
  expect(external).toEqual([]);
});

test.describe("JavaScript 없이 제출", () => {
  test.use({ javaScriptEnabled: false });

  test("스크립트가 동작하기 전에 제출해도 비밀번호가 주소에 남지 않음", async ({ page }) => {
    await page.goto("/login");
    await page.getByLabel("이메일").fill(email);
    await page.getByLabel("비밀번호").fill(password);
    await page.getByRole("button", { name: "로그인" }).click();
    await page.waitForLoadState();

    // method가 없던 폼은 브라우저 기본 GET 제출로 /login?email=...&password=... 로 이동했음
    expect(page.url()).not.toContain(encodeURIComponent(password));
    expect(page.url()).not.toContain("password=");
  });
});
