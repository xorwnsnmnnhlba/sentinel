import { expect, test } from "./fixtures";

/**
 * 콘텐츠 보안 정책(CSP). 위반 0건 확인은 모든 테스트 공통(fixtures.ts), 여기서는 정책 자체를 검증
 */
function nonceOf(policy: string | undefined): string {
  const nonce = policy?.match(/script-src 'self' 'nonce-([^']+)' 'strict-dynamic'/)?.[1];
  expect(nonce, policy).toBeDefined();
  return nonce!;
}

test("화면 응답마다 새 nonce로 정책 발급", async ({ request }) => {
  const first = (await request.get("/login")).headers()["content-security-policy"];
  const second = (await request.get("/login")).headers()["content-security-policy"];

  expect(nonceOf(first)).not.toBe(nonceOf(second));
  // 운영 빌드에는 개발 서버용 완화 없음
  expect(first).not.toContain("unsafe-eval");
  expect(first).toContain("object-src 'none'");
  expect(first).toContain("frame-ancestors 'none'");
});

test("페이지의 모든 스크립트에 응답 헤더의 nonce 부여", async ({ request }) => {
  const response = await request.get("/login");
  const nonce = nonceOf(response.headers()["content-security-policy"]);
  const scripts = (await response.text()).match(/<script\b[^>]*>/g) ?? [];

  expect(scripts.length).toBeGreaterThan(0);
  for (const script of scripts) {
    expect(script).toContain(`nonce="${nonce}"`);
  }
});

test("주입된 인라인 스크립트는 실행되지 않음", async ({ page, cspViolations }) => {
  await page.goto("/login");

  // 저장형 XSS 흉내: 이벤트 핸들러 속성이 든 HTML 삽입
  await page.evaluate(() => {
    document.body.insertAdjacentHTML("beforeend", `<img src="data:," onerror="window.__injected = true">`);
  });

  await expect.poll(() => cspViolations.length).toBeGreaterThan(0);
  expect(await page.evaluate(() => (window as unknown as { __injected?: boolean }).__injected)).toBeUndefined();
  expect(cspViolations.join("\n")).toContain("script-src-attr");
  // 의도한 위반이므로 공통 확인 대상에서 제외
  cspViolations.length = 0;
});
