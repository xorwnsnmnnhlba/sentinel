import AxeBuilder from "@axe-core/playwright";

import { expect, test, type Page } from "./fixtures";

/**
 * 접근성 자동 검사 (axe-core). WCAG 2.2 A·AA 규칙과 axe 권장 규칙(best-practice: 제목 순서, 랜드마크 등). 라이트·다크 모두
 * 실행 결과 배지·차트가 보이도록 통과·실패·오류가 섞인 실행을 API로 만든 뒤 주요 화면과 열린 메뉴·대화상자·서랍 검사
 * 비활성(disabled) 컨트롤은 WCAG 대비 기준 제외 대상
 */
test.describe.configure({ mode: "serial" });

/**
 * 검토 후 허용한 위반. 규칙 id와 요소 선택자로 지정하고 사유를 남김
 * - region: Radix가 떠 있는 메뉴(role=menu)를 body 끝에 그려 랜드마크 밖에 위치. 메뉴는 main 안의 "⋯" 버튼이
 *   aria-controls로 가리키고 열면 포커스가 메뉴로 이동하므로 랜드마크 탐색과 무관 (대화상자는 axe가 원래 제외)
 */
const ACCEPTED = [{ rule: "region", target: "[data-radix-popper-content-wrapper]" }];

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

  ids.project = (await create("/api/projects", { name: `a11y-${stamp}`, description: "접근성 검사" })).id;
  const environment = await create("/api/environments", {
    projectId: ids.project,
    name: "self",
    baseUrl: "http://e2e-api:8080",
    variables: { prefix: "/api" },
  });
  ids.suite = (await create("/api/suites", { projectId: ids.project, name: `a11y-suite-${stamp}` })).id;
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
  // 좁은 화면은 사이드바 대신 메뉴 서랍이므로 화면 이동으로 로그인 완료 확인
  await expect(page).toHaveURL(/\/$/);
}

/** 접근성 위반. "[규칙] 선택자 — 사유" 목록 (허용 목록 제외) */
async function accessibilityViolations(page: Page): Promise<string[]> {
  // 글꼴 적용과 열림 애니메이션(투명도 변화)이 끝난 뒤 검사
  await page.evaluate(() => document.fonts.ready);
  await page.waitForFunction(() => document.getAnimations().every((animation) => animation.playState !== "running"));
  const results = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa", "best-practice"])
    .analyze();
  // 태그 설정이 어긋나 일부 규칙만 도는 경우 방지 (색 대비만 검사하면 규칙 1~2개)
  expect(results.passes.length + results.violations.length).toBeGreaterThan(20);
  return results.violations.flatMap((violation) =>
    violation.nodes
      .filter(
        (node) =>
          !ACCEPTED.some(
            (accepted) =>
              accepted.rule === violation.id && node.target.join(" ").includes(accepted.target.slice(1, -1)),
          ),
      )
      .map((node) => {
        const reason = [...node.any, ...node.all, ...node.none][0]?.message ?? violation.help;
        return `[${violation.id}] ${node.target.join(" ")} — ${reason}`;
      }),
  );
}

for (const colorScheme of ["light", "dark"] as const) {
  test.describe(colorScheme === "light" ? "라이트" : "다크", () => {
    test.use({ colorScheme });

    test("로그인 화면", async ({ page }) => {
      await page.goto("/login");
      expect(await accessibilityViolations(page)).toEqual([]);
    });

    test("주요 화면", async ({ page }) => {
      await login(page);
      const paths = [
        "/",
        "/projects",
        `/projects/${ids.project}`,
        `/suites/${ids.suite}`,
        `/cases/new?suiteId=${ids.suite}`,
        `/cases/${ids.passingCase}`,
        "/runs",
        `/runs/${ids.failingRun}`,
        "/users",
        "/settings/password",
      ];
      for (const path of paths) {
        await page.goto(path);
        expect.soft(await accessibilityViolations(page), path).toEqual([]);
      }
    });

    test("열린 메뉴와 대화상자", async ({ page }) => {
      await login(page);
      /** 대화상자를 열고 검사한 뒤 Escape로 닫음 */
      async function inspectDialog(name: string, open: () => Promise<void>) {
        await open();
        await expect(page.getByRole("dialog").or(page.getByRole("alertdialog"))).toBeVisible();
        expect.soft(await accessibilityViolations(page), name).toEqual([]);
        await page.keyboard.press("Escape");
        await expect(page.getByRole("dialog").or(page.getByRole("alertdialog"))).toHaveCount(0);
      }

      await page.goto(`/suites/${ids.suite}`);
      await inspectDialog("삭제 확인", async () => {
        await page.getByRole("button", { name: "케이스 없는 초대 조회 작업" }).click();
        await expect(page.getByRole("menu")).toBeVisible();
        expect.soft(await accessibilityViolations(page), "행 메뉴").toEqual([]);
        await page.getByRole("menuitem", { name: "삭제" }).click();
      });
      await inspectDialog("스케줄 추가", () => page.getByRole("button", { name: "스케줄 추가" }).click());
      await inspectDialog("문서 가져오기", () => page.getByRole("button", { name: "문서 가져오기" }).click());

      await page.goto(`/projects/${ids.project}`);
      await inspectDialog("환경 수정 (변수·시크릿)", async () => {
        await page.getByRole("button", { name: "환경 self 작업" }).click();
        await page.getByRole("menuitem", { name: "수정" }).click();
      });

      await page.goto("/projects");
      await inspectDialog("등록 대화상자", () => page.getByRole("button", { name: "새 프로젝트" }).click());

      await page.goto("/users");
      await inspectDialog("사용자 초대", () => page.getByRole("button", { name: "사용자 초대" }).click());
    });

    test("좁은 화면의 메뉴 서랍", async ({ page }) => {
      await page.setViewportSize({ width: 390, height: 844 });
      await login(page);
      await page.getByRole("button", { name: "메뉴 열기" }).click();
      await expect(page.getByRole("dialog", { name: "메뉴" })).toBeVisible();
      expect(await accessibilityViolations(page)).toEqual([]);
    });
  });
}
