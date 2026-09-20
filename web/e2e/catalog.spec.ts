import { expect, type Page, test } from "@playwright/test";

/**
 * 등록 → 실행 → 스케줄 → 삭제 한 흐름. 실제 스택(PostgreSQL·api·web) 대상
 *
 * 케이스는 api 자신을 호출하여 외부 의존 없이 실행 결과를 검증 (컨테이너 안에서 http://e2e-api:8080)
 */
test.describe.configure({ mode: "serial" });

// 스택을 내리지 않고 반복 실행해도 충돌하지 않도록 매번 고유한 이름 사용
const stamp = Date.now();
const project = `e2e-${stamp}`;
const suite = `smoke-${stamp}`;
const secret = `secret-value-${stamp}`;

/** 실행 이력 화면의 전체 건수 */
async function totalRuns(page: Page): Promise<number> {
  const summary = await page.getByText(/전체 \d+건/).innerText();
  return Number(summary.match(/전체 (\d+)건/)![1]);
}

/** 화면의 오류 메시지. Next.js 라우트 알림(role=alert)은 제외 */
function alertIn(page: Page) {
  return page.locator('[role="alert"]:not(#__next-route-announcer__)');
}

test("프로젝트·환경·스위트 등록", async ({ page }) => {
  await page.goto("/projects");
  await page.getByRole("button", { name: "새 프로젝트" }).click();
  await page.getByLabel("이름", { exact: true }).fill(project);
  await page.getByLabel(/설명/).fill("E2E 테스트용");
  await page.getByRole("button", { name: "등록" }).click();

  await expect(page).toHaveURL(/\/projects\/\d+$/);
  await expect(page.getByRole("heading", { name: project })).toBeVisible();

  await page.getByRole("button", { name: "환경 추가" }).click();
  await page.getByLabel("이름", { exact: true }).fill("self");
  await page.getByLabel("기본 URL").fill("http://e2e-api:8080");
  await page.getByLabel("변수 1 이름").fill("apiPrefix");
  await page.getByLabel("변수 1 값").fill("/api");
  await page.getByRole("button", { name: "등록" }).click();

  const environments = page.getByRole("row", { name: /self/ });
  await expect(environments).toContainText("http://e2e-api:8080");
  await expect(environments).toContainText("apiPrefix");

  // 시크릿 추가: 이름만 목록에 표시되고 값은 어디에도 남지 않음
  await environments.getByRole("button", { name: /환경 self 수정/ }).click();
  await page.getByRole("button", { name: "시크릿 추가" }).click();
  await page.getByLabel("새 시크릿 1 이름").fill("token");
  await page.getByLabel("새 시크릿 1 값").fill(secret);
  await page.getByRole("button", { name: "저장" }).click();

  await expect(page.getByRole("row", { name: /self/ })).toContainText("token");
  expect(await page.content()).not.toContain(secret);

  await page.getByRole("button", { name: "스위트 추가" }).click();
  await page.getByLabel("이름", { exact: true }).fill(suite);
  await page.getByRole("button", { name: "등록" }).click();

  await expect(page.getByRole("row", { name: new RegExp(suite) })).toBeVisible();
});

test("이미 있는 이름으로 등록하면 오류", async ({ page }) => {
  await page.goto("/projects");
  await page.getByRole("button", { name: "새 프로젝트" }).click();
  await page.getByLabel("이름", { exact: true }).fill(project);
  await page.getByRole("button", { name: "등록" }).click();

  await expect(alertIn(page)).toHaveText("이미 사용 중인 이름입니다");
  // 입력값은 유지되고 목록에는 추가되지 않음
  await expect(page.getByLabel("이름", { exact: true })).toHaveValue(project);
});

test("케이스 등록 후 실행하면 결과 표시", async ({ page }) => {
  await page.goto("/projects");
  await page.getByRole("link", { name: project }).click();
  await page.getByRole("link", { name: suite }).click();

  await page.getByRole("link", { name: "케이스 추가" }).click();
  await page.getByLabel("이름", { exact: true }).fill("프로젝트 목록 조회");
  await page.getByLabel("경로").fill("{{apiPrefix}}/projects");
  await page.getByLabel("추가할 판정 규칙").selectOption("bodyContains");
  await page.getByRole("button", { name: "규칙 추가" }).click();
  await page.getByLabel("판정 규칙 1 포함할 문자열").fill(project);
  await page.getByRole("button", { name: "등록" }).click();

  await expect(page).toHaveURL(/\/suites\/\d+$/);
  await expect(page.getByRole("row", { name: /프로젝트 목록 조회/ })).toContainText("{{apiPrefix}}/projects");

  await page.getByRole("button", { name: "지금 실행" }).click();

  await expect(page).toHaveURL(/\/runs\/\d+$/);
  await expect(page.getByRole("heading", { name: new RegExp(suite) })).toBeVisible();
  await expect(page.getByText("통과 1")).toBeVisible();
  await expect(page.getByText("프로젝트 목록 조회")).toBeVisible();
});

test("잘못된 경로는 저장 전에 막힘", async ({ page }) => {
  await page.goto("/projects");
  await page.getByRole("link", { name: project }).click();
  await page.getByRole("link", { name: suite }).click();
  await page.getByRole("link", { name: "케이스 추가" }).click();

  await page.getByLabel("이름", { exact: true }).fill("잘못된 경로");
  await page.getByLabel("경로").fill("api/projects");
  await page.getByRole("button", { name: "등록" }).click();

  await expect(alertIn(page)).toContainText("경로는 / 또는 {{변수}}로 시작해야 합니다");
  await expect(page).toHaveURL(/\/cases\/new/);
});

test("스케줄 등록·전환·수정·삭제", async ({ page }) => {
  await page.goto("/projects");
  await page.getByRole("link", { name: project }).click();
  await page.getByRole("link", { name: suite }).click();

  await page.getByRole("button", { name: "스케줄 추가" }).click();
  await page.getByLabel("cron").fill("10 9 * * 1-5");
  await page.getByRole("button", { name: "등록" }).click();

  const row = page.getByRole("row", { name: /10 9 \* \* 1-5/ });
  await expect(row).toContainText("self");
  await expect(row.getByRole("switch")).toBeChecked();

  await row.getByRole("switch").click();
  await expect(row.getByRole("switch")).not.toBeChecked();
  await expect(row).toContainText("비활성");

  await row.getByRole("button", { name: /수정/ }).click();
  await page.getByLabel("cron").fill("0 0 30 2 *");
  await page.getByRole("button", { name: "저장" }).click();
  await expect(alertIn(page)).toContainText("입력값을 확인해 주세요");

  await page.getByLabel("cron").fill("30 17 * * 1-5");
  await page.getByRole("button", { name: "저장" }).click();
  await expect(page.getByRole("row", { name: /30 17 \* \* 1-5/ })).toBeVisible();

  await page.getByRole("button", { name: /스케줄 "30 17 \* \* 1-5" 삭제/ }).click();
  await page.getByRole("alertdialog").getByRole("button", { name: "삭제" }).click();
  await expect(page.getByText("등록된 스케줄이 없습니다")).toBeVisible();
});

test("프로젝트 삭제 시 하위 항목도 사라지고 실행 이력은 유지", async ({ page }) => {
  await page.goto("/runs");
  const runRow = page.getByRole("row", { name: new RegExp(suite) });
  await expect(runRow).toHaveCount(1);
  const totalBefore = await totalRuns(page);

  await page.goto("/projects");
  await page.getByRole("link", { name: project }).click();
  // 환경·스위트 행의 삭제 버튼과 구분 (프로젝트 삭제는 머리말의 텍스트 버튼)
  await page.getByRole("button", { name: "삭제", exact: true }).click();
  await page.getByRole("alertdialog").getByRole("button", { name: "삭제" }).click();

  await expect(page).toHaveURL(/\/projects$/);
  await expect(page.getByRole("link", { name: project })).toHaveCount(0);

  await page.goto("/runs");
  // 스위트가 삭제되어도 이력 건수와 스위트 이름은 그대로
  expect(await totalRuns(page)).toBe(totalBefore);
  await expect(runRow).toHaveCount(1);
});

test("없는 주소는 404", async ({ page }) => {
  for (const path of ["/projects/999999", "/suites/999999", "/cases/999999", "/runs/999999"]) {
    const response = await page.goto(path);
    expect(response?.status(), path).toBe(404);
    await expect(page.getByRole("heading", { level: 1 })).toContainText("찾을 수 없습니다");
  }
});
