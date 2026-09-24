import { expect, type Page, test } from "@playwright/test";

/**
 * 등록 → 실행 → 스케줄 → 삭제 한 흐름. 실제 스택(PostgreSQL·api·web) 대상
 *
 * 케이스는 api 자신을 호출하여 외부 의존 없이 실행 결과를 검증 (컨테이너 안에서 http://e2e-api:8080)
 */
test.describe.configure({ mode: "serial" });

/** 모든 화면이 로그인 필요. 스택의 최초 관리자 계정으로 로그인 후 진행 */
test.beforeEach(async ({ page }) => {
  await page.goto("/login");
  await page.getByLabel("이메일").fill("admin@sentinel.test");
  await page.getByLabel("비밀번호").fill("e2e-admin-password");
  await page.getByRole("button", { name: "로그인" }).click();
  // 머리말 메뉴가 보이면 로그인 완료 (홈 화면 카드 링크와 구분)
  await expect(page.getByRole("banner").getByRole("link", { name: "프로젝트" })).toBeVisible();
});

// 스택을 내리지 않고 반복 실행해도 충돌하지 않도록 매번 고유한 이름 사용
const stamp = Date.now();
const project = `e2e-${stamp}`;
const suite = `smoke-${stamp}`;
/** 케이스가 Sentinel API 자신을 호출하므로 실제 세션 토큰이 필요. 이 값을 시크릿으로 저장하여 마스킹까지 확인 */
let secret: string;

test.beforeAll(async ({ request }) => {
  const response = await request.post(`${process.env.E2E_API_URL ?? "http://localhost:18080"}/api/sessions`, {
    data: { email: "admin@sentinel.test", password: "e2e-admin-password" },
  });
  expect(response.status()).toBe(201);
  secret = (await response.json()).token;
});

/** 실행 이력 화면의 전체 건수 */
async function totalRuns(page: Page): Promise<number> {
  const summary = await page.getByText(/전체 \d+건/).innerText();
  return Number(summary.match(/전체 (\d+)건/)![1]);
}

/** 스위트 화면 케이스 목록의 행. 응답시간 표에도 같은 이름이 있어 표를 지정 */
function caseRow(page: Page, name: RegExp) {
  return page.getByRole("table", { name: "케이스 목록" }).getByRole("row", { name });
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
  await page.getByLabel("경로").fill("{{apiPrefix}}/projects?key={{token}}");
  // API 호출에도 세션이 필요하므로 시크릿을 인증 헤더로 사용
  await page.getByRole("button", { name: "헤더 추가" }).click();
  await page.getByLabel("헤더 1 이름").fill("Authorization");
  await page.getByLabel("헤더 1 값").fill("Bearer {{token}}");
  await page.getByLabel("추가할 판정 규칙").selectOption("bodyContains");
  await page.getByRole("button", { name: "규칙 추가" }).click();
  await page.getByLabel("판정 규칙 1 포함할 문자열").fill(project);
  await page.getByRole("button", { name: "등록" }).click();

  await expect(page).toHaveURL(/\/suites\/\d+$/);
  await expect(caseRow(page, /프로젝트 목록 조회/)).toContainText("{{apiPrefix}}/projects");

  await page.getByRole("button", { name: "지금 실행" }).click();

  await expect(page).toHaveURL(/\/runs\/\d+$/);
  await expect(page.getByRole("heading", { name: new RegExp(suite) })).toBeVisible();
  await expect(page.getByText("통과 1")).toBeVisible();
  await expect(page.getByText("프로젝트 목록 조회")).toBeVisible();
  // 수동 실행은 실행한 사람이 이력에 남음 (머리말의 로그인 계정과 구분하기 위해 본문에서 확인)
  await expect(page.getByRole("main").getByText("admin@sentinel.test")).toBeVisible();
  // 통과한 케이스는 접혀 있으므로 펼쳐서 확인. 시크릿(세션 토큰) 값은 마스킹된 상태로 표시
  await page.getByRole("button", { name: /프로젝트 목록 조회/ }).click();
  await expect(page.getByText(/key=\*\*\*\*/)).toBeVisible();
  expect(await page.content()).not.toContain(secret);
});

test("스위트 화면에서 응답시간 확인", async ({ page }) => {
  await page.goto("/projects");
  await page.getByRole("link", { name: project }).click();
  await page.getByRole("link", { name: suite }).click();

  const section = page.locator("#response-times");
  await expect(section.getByRole("heading", { name: "응답시간" })).toBeVisible();
  // 방금 실행한 1건이 집계되고, 가장 느린 케이스의 일별 차트가 기본으로 표시
  await expect(
    section.getByRole("table", { name: "케이스별 응답시간" }).getByRole("row", { name: /프로젝트 목록 조회/ }),
  ).toContainText("1");
  await expect(section.getByRole("img", { name: /프로젝트 목록 조회 · self · 최근 7일/ })).toBeVisible();

  await section.getByRole("button", { name: "표로 보기" }).click();
  await expect(section.getByRole("row").filter({ hasText: /ms/ }).last()).toContainText("1");

  // 조건은 주소에 남음
  await section.getByLabel("기간").selectOption("30");
  await section.getByRole("button", { name: "조회" }).click();
  await expect(page).toHaveURL(/days=30&environmentId=\d+&caseId=\d+#response-times$/);
  await expect(section.getByRole("img", { name: /최근 30일/ })).toBeVisible();
});

test("홈 대시보드에서 실행 현황 확인", async ({ page }) => {
  await page.goto("/");

  await expect(page.getByRole("heading", { name: "대시보드", level: 1 })).toBeVisible();
  const tiles = page.getByRole("region", { name: "요약" });
  await expect(tiles.getByText("통과율")).toBeVisible();
  await expect(tiles.getByText("실패·오류가 있는 실행")).toBeVisible();
  // 방금 실행한 스위트: 케이스 1건 통과
  const row = page.getByRole("table", { name: "스위트별 통과율" }).getByRole("row", { name: new RegExp(suite) });
  await expect(row).toContainText("100%");
  await expect(row.getByRole("meter", { name: `${suite} 통과율` })).toHaveAttribute("aria-valuenow", "100");

  // 기간은 주소에 남음
  await page.getByRole("link", { name: "최근 30일" }).click();
  await expect(page).toHaveURL(/\/\?days=30$/);
  await expect(page.getByRole("link", { name: "최근 30일" })).toHaveAttribute("aria-current", "page");

  // 실패 목록 전체는 실행 이력의 실패만 보기로 이동
  await page.getByRole("link", { name: "모두 보기" }).click();
  await expect(page).toHaveURL(/\/runs\?failing=true&from=\d{4}-\d{2}-\d{2}&to=\d{4}-\d{2}-\d{2}$/);
  await expect(page.getByRole("checkbox", { name: "실패·오류만" })).toBeChecked();
});

test("실행 이력을 조건으로 좁히고 초기화", async ({ page }) => {
  await page.goto("/runs");
  const runRow = page.getByRole("row", { name: new RegExp(suite) });
  await expect(runRow).toHaveCount(1);

  // 조건은 주소에 남아 새로고침해도 유지됨
  await page.getByLabel("프로젝트").selectOption({ label: project });
  await page.getByLabel("스위트").selectOption({ label: suite });
  // 환경 이름은 프로젝트마다 겹칠 수 있어 이 프로젝트 묶음 안의 항목을 선택
  const environment = page.getByLabel("환경").locator(`optgroup[label="${project}"] option`).first();
  await page.getByLabel("환경").selectOption((await environment.getAttribute("value"))!);
  await page.getByLabel("상태").selectOption({ label: "완료" });
  await page.getByRole("button", { name: "조회" }).click();

  await expect(page).toHaveURL(/projectId=\d+&suiteId=\d+&environmentId=\d+&status=COMPLETED/);
  await expect(runRow).toHaveCount(1);
  await page.reload();
  await expect(runRow).toHaveCount(1);

  // 앞으로의 날짜로 좁히면 결과 없음
  await page.getByLabel("시작일 (KST)").fill(afterToday());
  await page.getByRole("button", { name: "조회" }).click();
  await expect(page.getByText("조건에 맞는 실행 이력이 없습니다")).toBeVisible();

  await page.getByRole("link", { name: "초기화" }).click();
  await expect(page).toHaveURL(/\/runs$/);
  await expect(runRow).toHaveCount(1);
});

/** 어떤 시간대에서도 오늘보다 뒤인 날짜 */
function afterToday(): string {
  return new Date(Date.now() + 2 * 24 * 60 * 60 * 1000).toISOString().slice(0, 10);
}

test("OpenAPI 문서에서 케이스 가져오기", async ({ page }) => {
  await page.goto("/projects");
  await page.getByRole("link", { name: project }).click();
  await page.getByRole("link", { name: suite }).click();

  await page.getByRole("button", { name: "문서 가져오기" }).click();
  await page.getByLabel("문서", { exact: true }).fill(`openapi: 3.0.3
info: { title: Sentinel, version: "1" }
servers: [ { url: "http://e2e-api:8080" } ]
paths:
  /api/projects:
    get: { summary: 프로젝트 목록 조회, responses: { "200": { description: ok } } }
  /api/projects/{projectId}:
    get: { summary: 프로젝트 조회, responses: { "200": { description: ok } } }
  /api/suites:
    post:
      summary: 스위트 등록
      requestBody:
        content:
          application/json: { example: { projectId: 1, name: smoke } }
      responses: { "201": { description: created } }
`);
  await page.getByRole("button", { name: "미리보기" }).click();

  const dialog = page.getByRole("dialog");
  await expect(dialog.getByText("후보 3건")).toBeVisible();
  // 스위트에 이미 있는 이름은 선택 해제 상태
  await expect(dialog.getByRole("checkbox", { name: "GET /api/projects 가져오기" })).not.toBeChecked();
  await dialog.getByRole("textbox", { name: "POST /api/suites 이름" }).fill("스위트 등록 (가져옴)");
  await dialog.getByRole("button", { name: "2건 가져오기" }).click();

  await expect(dialog).toBeHidden();
  await expect(caseRow(page, /프로젝트 조회/)).toContainText("/api/projects/{{projectId}}");
  await expect(caseRow(page, /스위트 등록 \(가져옴\)/)).toContainText("/api/suites");
  // 기존 케이스는 그대로 한 건
  await expect(caseRow(page, /프로젝트 목록 조회/)).toHaveCount(1);
});

test("Postman 컬렉션에서 케이스 가져오기", async ({ page }) => {
  await page.goto("/projects");
  await page.getByRole("link", { name: project }).click();
  await page.getByRole("link", { name: suite }).click();

  await page.getByRole("button", { name: "문서 가져오기" }).click();
  await page.getByLabel("형식").selectOption("POSTMAN");
  await page.getByLabel("문서", { exact: true }).fill(
    JSON.stringify({
      info: { name: "Shop", schema: "https://schema.getpostman.com/json/collection/v2.1.0/collection.json" },
      auth: { type: "bearer", bearer: [{ key: "token", value: "literal-e2e-token" }] },
      item: [
        {
          name: "주문",
          item: [
            { name: "목록", request: { method: "GET", url: "{{baseUrl}}/api/orders?page=1" } },
            { name: "상세", request: { method: "GET", url: "{{baseUrl}}/api/orders/:orderId" } },
          ],
        },
      ],
    }),
  );
  await page.getByRole("button", { name: "미리보기" }).click();

  const dialog = page.getByRole("dialog");
  await expect(dialog.getByText("후보 2건")).toBeVisible();
  // 평문 토큰은 변수로 바뀌었다는 안내만 표시되고 값은 화면에 없음
  await expect(dialog.getByText("add {{bearerToken}} as a secret of the environment (bearer token)")).toBeVisible();
  expect(await page.content()).not.toContain("literal-e2e-token");
  await dialog.getByRole("button", { name: "2건 가져오기" }).click();

  await expect(dialog).toBeHidden();
  await expect(caseRow(page, /주문 \/ 목록/)).toContainText("/api/orders?page=1");
  await expect(caseRow(page, /주문 \/ 상세/)).toContainText("/api/orders/{{orderId}}");
});

test("1MB가 넘는 OpenAPI 문서도 미리보기", async ({ page }) => {
  await page.goto("/projects");
  await page.getByRole("link", { name: project }).click();
  await page.getByRole("link", { name: suite }).click();

  // Server Action 기본 본문 한도(1MB)를 넘는 문서. next.config의 bodySizeLimit으로 허용
  const description = "x".repeat(2 * 1024 * 1024);
  await page.getByRole("button", { name: "문서 가져오기" }).click();
  await page.getByLabel("문서", { exact: true }).fill(`openapi: 3.0.3
info: { title: Large, version: "1", description: "${description}" }
paths:
  /api/health:
    get: { summary: 상태 확인, responses: { "200": { description: ok } } }
`);
  await page.getByRole("button", { name: "미리보기" }).click();

  await expect(page.getByRole("dialog").getByText("후보 1건")).toBeVisible();
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
