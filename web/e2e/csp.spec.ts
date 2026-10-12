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

test("/api로 시작하는 화면(API 문서)도 정책 적용과 로그인 확인", async ({ page, request }) => {
  // 로그인 전에는 앞단(proxy)이 로그인 화면으로 보냄
  const anonymous = await request.get("/api-docs", { maxRedirects: 0 });
  expect(anonymous.status()).toBe(307);
  expect(anonymous.headers()["location"]).toContain("/login");

  await page.goto("/login");
  await page.getByLabel("이메일").fill("admin@sentinel.test");
  await page.getByLabel("비밀번호").fill("e2e-admin-password");
  await page.getByRole("button", { name: "로그인" }).click();
  await page.waitForURL((url) => !url.pathname.startsWith("/login"));

  const response = await page.goto("/api-docs");
  nonceOf(response?.headers()["content-security-policy"]);
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

test("선택 상자·대화상자가 넣는 <style>에도 nonce가 있어 위반 없음", async ({ page, request, cspViolations }) => {
  // Radix 선택 목록은 스크롤바를 숨기는 <style>을, 대화상자는 스크롤 잠금 <style>을 그림. nonce가 없으면 차단되어 위반
  const api = process.env.E2E_API_URL ?? "http://localhost:18080";
  const session = await request.post(`${api}/api/sessions`, {
    data: { email: "admin@sentinel.test", password: "e2e-admin-password" },
  });
  const headers = { Authorization: `Bearer ${(await session.json()).token}` };
  const create = async (path: string, data: object) =>
    (await (await request.post(`${api}${path}`, { headers, data })).json()) as { id: number };
  const project = await create("/api/projects", { name: `csp-${Date.now()}` });
  await create("/api/environments", { projectId: project.id, name: "dev", baseUrl: "http://localhost" });
  await create("/api/environments", { projectId: project.id, name: "staging", baseUrl: "http://localhost" });
  const suite = await create("/api/suites", { projectId: project.id, name: "csp-suite" });

  await page.goto("/login");
  await page.getByLabel("이메일").fill("admin@sentinel.test");
  await page.getByLabel("비밀번호").fill("e2e-admin-password");
  await page.getByRole("button", { name: "로그인" }).click();
  await expect(page).toHaveURL(/\/$/);
  await page.goto(`/suites/${suite.id}`);

  const select = page.getByRole("combobox", { name: "실행 환경" });
  await select.click();
  await page.getByRole("option", { name: "staging" }).click();
  await expect(select).toHaveText("staging");

  await page.getByRole("button", { name: "스케줄 추가" }).click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog")).toBeHidden();

  // 공통 확인(fixtures.ts)과 별개로 여기서도 명시
  expect(cspViolations).toEqual([]);
});
