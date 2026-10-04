import { expect, mainMenu, sidebar, test, type Locator } from "./fixtures";

/**
 * 화면 틀 (DESIGN.md "배치"). 넓은 화면은 왼쪽 사이드바, 좁은 화면은 위쪽 막대의 메뉴 서랍
 */
const email = "admin@sentinel.test";

test.beforeEach(async ({ page }) => {
  await page.goto("/login");
  await page.getByLabel("이메일").fill(email);
  await page.getByLabel("비밀번호").fill("e2e-admin-password");
  await page.getByRole("button", { name: "로그인" }).click();
  await expect(mainMenu(page).getByRole("link", { name: "프로젝트" })).toBeVisible();
});

test("넓은 화면에서는 왼쪽 사이드바에 메뉴와 계정을 표시하고 현재 화면을 강조", async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 800 });
  await page.goto("/runs");

  const aside = (await sidebar(page).boundingBox())!;
  const main = (await page.getByRole("main").boundingBox())!;
  expect(aside.x).toBe(0);
  expect(aside.height).toBeGreaterThanOrEqual(800);
  expect(main.x).toBeGreaterThanOrEqual(aside.x + aside.width);

  await expect(mainMenu(page).getByRole("link", { name: "실행 이력" })).toHaveAttribute("aria-current", "page");
  await expect(mainMenu(page).getByRole("link", { name: "홈" })).not.toHaveAttribute("aria-current");
  await expect(sidebar(page).getByRole("link", { name: email })).toBeVisible();
  await expect(sidebar(page).getByRole("button", { name: "로그아웃" })).toBeVisible();
  // 좁은 화면용 메뉴 버튼은 숨김
  await expect(page.getByRole("button", { name: "메뉴 열기" })).toBeHidden();
});

test("좁은 화면에서는 메뉴 서랍으로 이동하고 가로 스크롤 없음", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/");

  await expect(sidebar(page)).toBeHidden();
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  expect(overflow).toBeLessThanOrEqual(0);

  await page.getByRole("button", { name: "메뉴 열기" }).click();
  const drawer = page.getByRole("dialog", { name: "메뉴" });
  await expect(drawer.getByRole("link", { name: email })).toBeVisible();
  await drawer.getByRole("link", { name: "실행 이력" }).click();

  await expect(page).toHaveURL(/\/runs$/);
  await expect(drawer).toBeHidden();
  await expect(page.getByRole("heading", { name: "실행 이력", level: 1 })).toBeVisible();
});

test("로그인 전 화면에는 사이드바·메뉴가 없음", async ({ page, context }) => {
  await context.clearCookies();
  await page.goto("/login");

  await expect(page.getByRole("heading", { name: "로그인" })).toBeVisible();
  await expect(sidebar(page)).toHaveCount(0);
  await expect(mainMenu(page)).toHaveCount(0);
  await expect(page.getByRole("button", { name: "메뉴 열기" })).toHaveCount(0);
});

test("누를 수 있는 요소는 손가락 커서, 비활성·입력칸은 그대로", async ({ page }) => {
  const cursor = (locator: Locator) => locator.evaluate((element) => getComputedStyle(element).cursor);

  await page.goto("/runs");
  expect(await cursor(page.getByRole("button", { name: "조회" }))).toBe("pointer");
  expect(await cursor(sidebar(page).getByRole("button", { name: "다크" }))).toBe("pointer");
  expect(await cursor(page.getByLabel("프로젝트"))).toBe("pointer");
  const failingOnly = page.getByRole("checkbox", { name: "실패·오류만" });
  expect(await cursor(failingOnly)).toBe("pointer");
  expect(await cursor(page.locator("label", { has: failingOnly }))).toBe("pointer");
  expect(await cursor(page.getByLabel("시작일 (KST)"))).not.toBe("pointer");

  // 파일 선택(문서 가져오기)은 손가락, 비활성 버튼은 그대로
  const cursorOf = (html: string) =>
    page.evaluate((markup) => {
      const holder = document.createElement("div");
      holder.innerHTML = markup;
      document.body.append(holder);
      const value = getComputedStyle(holder.firstElementChild!).cursor;
      holder.remove();
      return value;
    }, html);
  expect(await cursorOf('<input type="file">')).toBe("pointer");
  expect(await cursorOf("<button disabled>비활성</button>")).toBe("default");

  // 떠 있는 메뉴의 항목
  await page.goto("/users");
  await page.getByRole("button", { name: `${email} 작업` }).click();
  expect(await cursor(page.getByRole("menuitem", { name: "비밀번호 재설정 링크" }))).toBe("pointer");
});
