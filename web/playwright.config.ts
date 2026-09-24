import { defineConfig, devices } from "@playwright/test";

/**
 * E2E 테스트. PostgreSQL·api·web을 모두 띄운 실제 스택 대상 (docker-compose.e2e.yml)
 *
 *   npm run e2e:up && npm run e2e && npm run e2e:down
 *
 * 운영과 같은 HTTPS 앞단(Caddy)을 거쳐 접속. 개발/운영 스택과 포트가 겹치지 않도록 HTTPS 13443, api 18080 사용
 * 인증서는 Caddy 내부 인증 기관이 발급하므로 신뢰 검사 생략
 */
export default defineConfig({
  testDir: "./e2e",
  // 같은 DB를 공유하므로 순차 실행. 테스트마다 고유 이름으로 데이터 생성
  workers: 1,
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: 0,
  reporter: process.env.CI ? [["github"], ["html", { open: "never" }]] : [["list"]],
  use: {
    baseURL: process.env.E2E_WEB_URL ?? "https://localhost:13443",
    ignoreHTTPSErrors: true,
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    locale: "ko-KR",
    timezoneId: "Asia/Seoul",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
});
