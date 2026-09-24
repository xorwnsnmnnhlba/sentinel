import { expect, type Page, test } from "@playwright/test";

/**
 * 공통 머리말 배치. 좁은 화면에서 메뉴 글자가 한 글자씩 줄바꿈되던 문제 재발 방지
 */
const email = "admin@sentinel.test";

test.beforeEach(async ({ page }) => {
  await page.goto("/login");
  await page.getByLabel("이메일").fill(email);
  await page.getByLabel("비밀번호").fill("e2e-admin-password");
  await page.getByRole("button", { name: "로그인" }).click();
  await expect(page.getByRole("banner").getByRole("link", { name: "프로젝트" })).toBeVisible();
});

/** 한 줄 글자 높이(약 20px)보다 크면 줄바꿈된 것 */
async function expectSingleLine(page: Page) {
  const links = page.getByRole("navigation", { name: "주요 메뉴" }).getByRole("link");
  for (const link of await links.all()) {
    const box = (await link.boundingBox())!;
    expect(box.height, await link.innerText()).toBeLessThan(28);
  }
}

test("좁은 화면에서는 메뉴를 다음 줄에 한 줄로 표시하고 가로 스크롤 없음", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/");

  await expectSingleLine(page);
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  expect(overflow).toBeLessThanOrEqual(0);
  // 메뉴는 로고 아래 줄
  const brand = (await page.getByRole("banner").getByRole("link", { name: "Sentinel", exact: true }).boundingBox())!;
  const menu = (await page.getByRole("navigation", { name: "주요 메뉴" }).boundingBox())!;
  expect(menu.y).toBeGreaterThanOrEqual(brand.y + brand.height);
  // 계정·로그아웃은 아이콘이지만 이름은 유지
  await expect(page.getByRole("banner").getByRole("link", { name: email })).toBeVisible();
  await expect(page.getByRole("button", { name: "로그아웃" })).toBeVisible();
});

test("넓은 화면에서는 로고·메뉴·계정을 한 줄로 표시", async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 800 });
  await page.goto("/");

  await expectSingleLine(page);
  const brand = (await page.getByRole("banner").getByRole("link", { name: "Sentinel", exact: true }).boundingBox())!;
  const menu = (await page.getByRole("navigation", { name: "주요 메뉴" }).boundingBox())!;
  const account = (await page.getByRole("banner").getByRole("link", { name: email }).boundingBox())!;
  for (const box of [menu, account]) {
    expect(Math.abs(box.y + box.height / 2 - (brand.y + brand.height / 2))).toBeLessThan(6);
  }
  await expect(page.getByRole("banner").getByText(email)).toBeVisible();
});
