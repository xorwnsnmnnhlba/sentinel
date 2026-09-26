import { expect, sidebar, test, type Page } from "./fixtures";

/**
 * 테마 (DESIGN.md "테마"). 기본은 운영체제 설정을 따르고, 사이드바에서 고르면 쿠키로 유지
 */
const LIGHT = "rgb(255, 255, 255)";
const DARK = "rgb(17, 12, 28)";

test.beforeEach(async ({ page }) => {
  await page.goto("/login");
  await page.getByLabel("이메일").fill("admin@sentinel.test");
  await page.getByLabel("비밀번호").fill("e2e-admin-password");
  await page.getByRole("button", { name: "로그인" }).click();
  await expect(sidebar(page)).toBeVisible();
});

/** 본문 바탕색 (body의 --background) */
function background(page: Page) {
  return page.evaluate(() => getComputedStyle(document.body).backgroundColor);
}

test("처음에는 운영체제 설정을 따름", async ({ page }) => {
  await page.emulateMedia({ colorScheme: "dark" });
  await expect.poll(() => background(page)).toBe(DARK);

  await page.emulateMedia({ colorScheme: "light" });
  await expect.poll(() => background(page)).toBe(LIGHT);
  await expect(sidebar(page).getByRole("button", { name: "시스템 설정" })).toHaveAttribute("aria-pressed", "true");
});

test("고른 테마는 운영체제 설정보다 우선하고 새로 고쳐도 유지", async ({ page }) => {
  await page.emulateMedia({ colorScheme: "light" });

  await sidebar(page).getByRole("button", { name: "다크" }).click();
  await expect.poll(() => background(page)).toBe(DARK);

  // 저장 요청이 끝난 뒤 새로 고침. 서버가 쿠키로 첫 화면부터 다크로 렌더링
  await expect
    .poll(async () => (await page.context().cookies()).find((c) => c.name === "sentinel_theme")?.value)
    .toBe("dark");
  const response = await page.reload();
  expect(await response!.text()).toContain('data-theme="dark"');
  expect(await background(page)).toBe(DARK);
  await expect(sidebar(page).getByRole("button", { name: "다크" })).toHaveAttribute("aria-pressed", "true");

  await sidebar(page).getByRole("button", { name: "라이트" }).click();
  await page.emulateMedia({ colorScheme: "dark" });
  await expect.poll(() => background(page)).toBe(LIGHT);
});

/** 글자색과 바탕색의 WCAG 대비. 둘 다 불투명한 색일 때만 의미 있음 */
function contrast(element: Element): number {
  const style = getComputedStyle(element);
  const luminance = (color: string) => {
    const [r, g, b] = color
      .match(/[\d.]+/g)!
      .slice(0, 3)
      .map((value) => {
        const channel = Number(value) / 255;
        return channel <= 0.03928 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4;
      });
    return 0.2126 * r + 0.7152 * g + 0.0722 * b;
  };
  const [light, dark] = [luminance(style.color), luminance(style.backgroundColor)].sort((a, b) => b - a);
  return (light + 0.05) / (dark + 0.05);
}

test("다크에서도 실행 버튼 옆 환경 선택 글자가 읽힘", async ({ page, request }) => {
  // shadcn 선택 상자의 다크 전용 바탕(dark:bg-input/30)이 주 버튼 색을 덮어 글자가 사라졌던 문제 재발 방지
  const api = process.env.E2E_API_URL ?? "http://localhost:18080";
  const session = await request.post(`${api}/api/sessions`, {
    data: { email: "admin@sentinel.test", password: "e2e-admin-password" },
  });
  const headers = { Authorization: `Bearer ${(await session.json()).token}` };
  const create = async (path: string, data: object) =>
    (await (await request.post(`${api}${path}`, { headers, data })).json()) as { id: number };
  const project = await create("/api/projects", { name: `theme-${Date.now()}` });
  await create("/api/environments", { projectId: project.id, name: "dev", baseUrl: "http://localhost" });
  const suite = await create("/api/suites", { projectId: project.id, name: "theme-suite" });

  for (const colorScheme of ["dark", "light"] as const) {
    await page.emulateMedia({ colorScheme });
    await page.goto(`/suites/${suite.id}`);
    const select = page.getByRole("combobox", { name: "실행 환경" });
    await expect(select).toHaveText("dev");
    expect(await select.evaluate(contrast), colorScheme).toBeGreaterThanOrEqual(4.5);
  }
});
