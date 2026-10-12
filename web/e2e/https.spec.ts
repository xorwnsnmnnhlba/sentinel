import { expect, test, mainMenu } from "./fixtures";

/**
 * HTTPS 앞단(Caddy). E2E 스택의 e2e-proxy가 운영과 같은 proxy/Caddyfile 사용
 */
const httpUrl = process.env.E2E_HTTP_URL ?? "http://localhost:13080";

test("HTTP로 접속하면 HTTPS로 이동", async ({ request }) => {
  const response = await request.get(`${httpUrl}/login?from=%2Fruns`, { maxRedirects: 0 });

  expect(response.status()).toBe(308);
  expect(response.headers()["location"]).toBe("https://localhost:13443/login?from=%2Fruns");
});

test("보안 헤더를 붙이고 서버 정보는 숨김", async ({ request }) => {
  const response = await request.get("/login");

  expect(response.status()).toBe(200);
  const headers = response.headers();
  expect(headers["x-content-type-options"]).toBe("nosniff");
  expect(headers["referrer-policy"]).toBe("strict-origin-when-cross-origin");
  expect(headers["x-frame-options"]).toBe("DENY");
  expect(headers["server"]).toBeUndefined();
});

test("HTTPS 앞단을 거쳐 로그인하면 Secure 세션 쿠키 발급", async ({ page, context }) => {
  await page.goto("/login");
  await page.getByLabel("이메일").fill("admin@sentinel.test");
  await page.getByLabel("비밀번호").fill("e2e-admin-password");
  await page.getByRole("button", { name: "로그인" }).click();

  // Server Action의 출처 확인(Origin과 Host 비교)이 앞단을 거쳐도 통과해야 로그인 완료
  await expect(mainMenu(page).getByRole("link", { name: "프로젝트" })).toBeVisible();
  expect(page.url()).toMatch(/^https:\/\/localhost:13443\//);
  // 운영 빌드는 __Host- 접두어 (Secure·Path=/·Domain 없음을 브라우저가 강제)
  const session = (await context.cookies()).find((cookie) => cookie.name === "__Host-sentinel_session");
  expect(session).toMatchObject({ secure: true, httpOnly: true, sameSite: "Lax", path: "/" });
});

test("로그인 상태여도 BFF 프록시로는 쓰기 요청 불가 (쓰기는 Server Action만)", async ({ page }) => {
  await page.goto("/login");
  await page.getByLabel("이메일").fill("admin@sentinel.test");
  await page.getByLabel("비밀번호").fill("e2e-admin-password");
  await page.getByRole("button", { name: "로그인" }).click();
  await expect(mainMenu(page).getByRole("link", { name: "프로젝트" })).toBeVisible();

  // 같은 브라우저 세션(쿠키)으로 보내도 조회만 전달
  expect((await page.request.get("/api/projects")).status()).toBe(200);
  const name = `bff-write-${Date.now()}`;
  for (const method of ["POST", "PUT", "PATCH", "DELETE"]) {
    const response = await page.request.fetch("/api/projects", { method, data: { name } });
    expect(response.status(), method).toBe(405);
  }
  const projects = (await (await page.request.get("/api/projects")).json()) as { name: string }[];
  expect(projects.map((project) => project.name)).not.toContain(name);
});
