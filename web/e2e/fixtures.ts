import AxeBuilder from "@axe-core/playwright";
import { type APIRequestContext, expect, type Page, test as base } from "@playwright/test";

/**
 * 느린 모드 지연(ms). E2E_SLOW_MS를 지정하면 앱 요청(화면 문서, 화면 전환 데이터(RSC), Server Action)을 그만큼 늦춤
 * 로컬처럼 응답이 빠를 때는 드러나지 않는 타이밍 문제(화면 전환 전 클릭 등)를 CI보다 먼저 찾기 위한 설정
 */
export const SLOW_MS = Number(process.env.E2E_SLOW_MS ?? 0);

/** 늦출 요청. 정적 파일·글꼴·이미지는 제외 */
const SLOWED_TYPES = new Set(["document", "fetch", "xhr"]);

/**
 * 모든 E2E 테스트 공통 확인: 콘텐츠 보안 정책(CSP) 위반 0건
 *
 * 브라우저의 securitypolicyviolation 이벤트를 테스트 쪽으로 전달하여 수집. 화면 이동 후에도 유지되도록 컨텍스트 단위로 등록
 * 정책이 화면 동작(스크립트·스타일·폰트·이미지)을 막으면 해당 테스트가 실패
 */
export const test = base.extend<{ cspViolations: string[]; slowNetwork: void }>({
  slowNetwork: [
    async ({ context }, use) => {
      if (SLOW_MS > 0) {
        await context.route("**/*", async (route) => {
          if (SLOWED_TYPES.has(route.request().resourceType())) {
            await new Promise((resolve) => setTimeout(resolve, SLOW_MS));
          }
          await route.fallback();
        });
      }
      await use();
    },
    { auto: true },
  ],
  cspViolations: [
    async ({ context }, use, testInfo) => {
      const violations: string[] = [];
      await context.exposeBinding("__reportCspViolation", ({ page }, violation: string) => {
        violations.push(`${page.url()} ${violation}`);
      });
      await context.addInitScript(() => {
        document.addEventListener("securitypolicyviolation", (event) => {
          const report = (window as unknown as { __reportCspViolation: (violation: string) => void })
            .__reportCspViolation;
          report(`${event.effectiveDirective} ${event.blockedURI || "inline"} ${event.sample}`.trim());
        });
      });

      await use(violations);

      expect(violations, `${testInfo.title}: CSP 위반`).toEqual([]);
    },
    { auto: true },
  ],
});

export { expect };
export type { Page } from "@playwright/test";

/**
 * API로 시작한 실행이 끝날 때까지 대기. 수동 실행은 요청에 바로 응답하고 백그라운드에서 진행하므로
 * 결과를 이어서 쓰는 준비 단계(이전 실행이 필요한 비교·구조 변화 등)에서 사용
 */
export async function waitForRun(
  request: APIRequestContext,
  api: string,
  headers: Record<string, string>,
  runId: number,
): Promise<void> {
  await expect
    .poll(
      async () => {
        const response = await request.get(`${api}/api/runs/${runId}`, { headers });
        return ((await response.json()) as { status: string }).status;
      },
      { message: `run ${runId} finishes`, timeout: 20_000 },
    )
    .not.toBe("RUNNING");
}

/** 사이드바 주요 메뉴 (넓은 화면) */
export function mainMenu(page: Page) {
  return page.getByRole("navigation", { name: "주요 메뉴" });
}

/** 사이드바. 아래쪽 계정 영역 포함 */
export function sidebar(page: Page) {
  return page.getByRole("complementary", { name: "사이드바" });
}

/** "⋯" 메뉴(접근 이름: "<대상> 작업")를 열고 항목 선택 */
export async function chooseAction(page: Page, menu: string | RegExp, item: string) {
  await page.getByRole("button", { name: menu }).click();
  await page.getByRole("menuitem", { name: item, exact: true }).click();
}

/**
 * 검토 후 허용한 위반. 규칙 id와 요소 선택자로 지정하고 사유를 남김
 * - region: Radix가 떠 있는 메뉴(role=menu)를 body 끝에 그려 랜드마크 밖에 위치. 메뉴는 main 안의 "⋯" 버튼이
 *   aria-controls로 가리키고 열면 포커스가 메뉴로 이동하므로 랜드마크 탐색과 무관 (대화상자는 axe가 원래 제외)
 */
const ACCEPTED = [{ rule: "region", target: "[data-radix-popper-content-wrapper]" }];

/** 접근성 위반. "[규칙] 선택자 — 사유" 목록 (허용 목록 제외) */
export async function accessibilityViolations(page: Page): Promise<string[]> {
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
