import AxeBuilder from "@axe-core/playwright";

import { expect, test, type Page } from "./fixtures";

/**
 * 글자 색 대비 자동 검사 (axe-core color-contrast, WCAG 2 AA: 본문 4.5:1, 큰 글자 3:1). 라이트·다크 모두
 * 실행 결과 배지·차트가 보이도록 통과·실패·오류가 섞인 실행을 API로 만든 뒤 주요 화면과 열린 메뉴·대화상자 검사
 */
test.describe.configure({ mode: "serial" });

const api = process.env.E2E_API_URL ?? "http://localhost:18080";
const stamp = Date.now();
const ids = { project: 0, suite: 0, passingCase: 0, failingRun: 0 };

test.beforeAll(async ({ request }) => {
  const session = await request.post(`${api}/api/sessions`, {
    data: { email: "admin@sentinel.test", password: "e2e-admin-password" },
  });
  const headers = { Authorization: `Bearer ${(await session.json()).token}` };
  const create = async (path: string, data: object) => {
    const response = await request.post(`${api}${path}`, { headers, data });
    expect(response.ok(), `${path} ${response.status()}`).toBe(true);
    return (await response.json()) as { id: number };
  };

  ids.project = (await create("/api/projects", { name: `contrast-${stamp}`, description: "색 대비 검사" })).id;
  const environment = await create("/api/environments", {
    projectId: ids.project,
    name: "self",
    baseUrl: "http://e2e-api:8080",
    variables: { prefix: "/api" },
  });
  ids.suite = (await create("/api/suites", { projectId: ids.project, name: `contrast-suite-${stamp}` })).id;
  const request_ = (path: string) => ({ method: "GET", path, headers: {}, body: null });
  // 통과: 없는 초대는 404 (로그인 불필요)
  ids.passingCase = (
    await create("/api/cases", {
      suiteId: ids.suite,
      name: "없는 초대 조회",
      request: request_("{{prefix}}/invites/unknown"),
      assertions: [{ type: "statusCode", anyOf: ["404"] }],
    })
  ).id;
  // 실패: 로그인 없이 목록 조회는 401
  await create("/api/cases", { suiteId: ids.suite, name: "프로젝트 목록", request: request_("{{prefix}}/projects") });
  // 오류: 없는 변수
  await create("/api/cases", { suiteId: ids.suite, name: "없는 변수", request: request_("/api/{{missing}}") });

  ids.failingRun = (await create("/api/runs", { suiteId: ids.suite, environmentId: environment.id })).id;
  // 실패·오류 0건 배지(흐리게 표시)가 나오도록 통과만 있는 실행도 추가
  await create("/api/runs", { caseId: ids.passingCase, environmentId: environment.id });
});

async function login(page: Page) {
  await page.goto("/login");
  await page.getByLabel("이메일").fill("admin@sentinel.test");
  await page.getByLabel("비밀번호").fill("e2e-admin-password");
  await page.getByRole("button", { name: "로그인" }).click();
  await expect(page.getByRole("navigation", { name: "주요 메뉴" })).toBeVisible();
}

/** 대비 기준 미달 요소. "선택자 — 사유" 목록 */
async function contrastViolations(page: Page): Promise<string[]> {
  // 글꼴 적용과 열림 애니메이션(투명도 변화)이 끝난 뒤 검사
  await page.evaluate(() => document.fonts.ready);
  await page.waitForFunction(() => document.getAnimations().every((animation) => animation.playState !== "running"));
  const results = await new AxeBuilder({ page }).withRules(["color-contrast"]).analyze();
  return results.violations.flatMap((violation) =>
    violation.nodes.map((node) => `${node.target.join(" ")} — ${node.any[0]?.message ?? violation.help}`),
  );
}

for (const colorScheme of ["light", "dark"] as const) {
  test.describe(colorScheme === "light" ? "라이트" : "다크", () => {
    test.use({ colorScheme });

    test("로그인 화면", async ({ page }) => {
      await page.goto("/login");
      expect(await contrastViolations(page)).toEqual([]);
    });

    test("주요 화면", async ({ page }) => {
      await login(page);
      const paths = [
        "/",
        "/projects",
        `/projects/${ids.project}`,
        `/suites/${ids.suite}`,
        `/cases/${ids.passingCase}`,
        "/runs",
        `/runs/${ids.failingRun}`,
        "/users",
        "/settings/password",
      ];
      for (const path of paths) {
        await page.goto(path);
        expect.soft(await contrastViolations(page), path).toEqual([]);
      }
    });

    test("열린 메뉴와 대화상자", async ({ page }) => {
      await login(page);
      await page.goto(`/suites/${ids.suite}`);

      await page.getByRole("button", { name: "케이스 없는 초대 조회 작업" }).click();
      await expect(page.getByRole("menu")).toBeVisible();
      expect.soft(await contrastViolations(page), "행 메뉴").toEqual([]);

      await page.getByRole("menuitem", { name: "삭제" }).click();
      await expect(page.getByRole("alertdialog")).toBeVisible();
      expect.soft(await contrastViolations(page), "삭제 확인").toEqual([]);
      await page.getByRole("button", { name: "취소" }).click();

      await page.goto("/projects");
      await page.getByRole("button", { name: "새 프로젝트" }).click();
      await expect(page.getByRole("dialog")).toBeVisible();
      expect.soft(await contrastViolations(page), "등록 대화상자").toEqual([]);
    });
  });
}
