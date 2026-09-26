import { expect, type Page, test as base } from "@playwright/test";

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
