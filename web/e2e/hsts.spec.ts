import { expect, test } from "./fixtures";

/**
 * HSTS (proxy/Caddyfile). E2E 스택은 내부 이름 역할의 sentinel.e2e.test에 max-age=300 지정
 * 브라우저가 이 이름을 127.0.0.1로 해석하도록 지정 (워커 단위 설정이라 파일 최상위에서 사용)
 */
test.use({ launchOptions: { args: ["--host-resolver-rules=MAP sentinel.e2e.test 127.0.0.1"] } });

test("내부 이름으로 접속하면 HSTS로 HTTPS 유지를 알림", async ({ page }) => {
  const response = await page.goto("https://sentinel.e2e.test:13443/login");

  expect(response!.status()).toBe(200);
  expect(response!.headers()["strict-transport-security"]).toBe("max-age=300");
  await expect(page.getByRole("heading", { name: "로그인" })).toBeVisible();
});

test("localhost에는 HSTS를 보내지 않음 (같은 PC의 http 개발 서버 보호)", async ({ request }) => {
  const response = await request.get("/login");

  expect(response.status()).toBe(200);
  expect(response.headers()["strict-transport-security"]).toBeUndefined();
});
