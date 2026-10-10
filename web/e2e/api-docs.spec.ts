import { accessibilityViolations, expect, sidebar, test } from "./fixtures";

/**
 * API 문서. Spring이 만든 OpenAPI 문서를 묶음별로 표시하고 JSON으로 내려받기
 */
test.beforeEach(async ({ page }) => {
  await page.goto("/login");
  await page.getByLabel("이메일").fill("admin@sentinel.test");
  await page.getByLabel("비밀번호").fill("e2e-admin-password");
  await page.getByRole("button", { name: "로그인" }).click();
  await expect(sidebar(page).getByRole("link", { name: "API 문서" })).toBeVisible();
});

test("묶음별 API를 펼쳐 조건·응답을 보고 데이터 형식으로 이동", async ({ page }) => {
  await sidebar(page).getByRole("link", { name: "API 문서" }).click();
  await expect(page.getByRole("heading", { name: "API 문서", level: 1 })).toBeVisible();

  const groups = page.getByRole("navigation", { name: "API 묶음" });
  await expect(groups.getByRole("link").first()).toHaveText(/로그인/);
  await groups.getByRole("link", { name: /휴일/ }).click();
  await expect(page).toHaveURL(/#group-\d+$/);

  // 접힌 API를 펼치면 설명·조건·응답 (설명은 컨트롤러 Javadoc)
  const list = page.locator("#op-get-api-holidays");
  await list.getByText("/api/holidays", { exact: true }).click();
  await expect(list).toContainText("생략 시 올해");
  await expect(list.getByRole("table", { name: "GET /api/holidays 조건" })).toContainText("year");
  expect(await accessibilityViolations(page)).toEqual([]);

  // 로그인 전에 쓰는 요청 표시
  await expect(page.locator("#op-post-api-sessions")).toContainText("로그인 불필요");

  await list.getByRole("link", { name: "HolidayItem" }).click();
  await expect(page).toHaveURL(/#schema-HolidayItem$/);
  await expect(page.getByRole("region", { name: "HolidayItem" })).toContainText("date");
});

test("JSON 내려받기는 같은 OpenAPI 문서", async ({ page }) => {
  await page.goto("/api-docs");
  const download = page.getByRole("link", { name: "JSON 내려받기" });
  await expect(download).toHaveAttribute("download", "sentinel-openapi.json");

  const response = await page.request.get((await download.getAttribute("href"))!);
  expect(response.ok()).toBe(true);
  const doc = (await response.json()) as { info: { title: string }; paths: Record<string, unknown> };
  expect(doc.info.title).toBe("Sentinel API");
  expect(Object.keys(doc.paths)).toContain("/api/holidays");
});
