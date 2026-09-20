import { defineConfig, devices } from "@playwright/test";

/**
 * E2E 테스트. PostgreSQL·api·web을 모두 띄운 실제 스택 대상 (docker-compose.e2e.yml)
 *
 *   npm run e2e:up && npm run e2e && npm run e2e:down
 *
 * 개발/운영 스택과 포트가 겹치지 않도록 web 13000, api 18080 사용
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
    baseURL: process.env.E2E_WEB_URL ?? "http://localhost:13000",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    locale: "ko-KR",
    timezoneId: "Asia/Seoul",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
});
