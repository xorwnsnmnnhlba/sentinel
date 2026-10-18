import { defineConfig } from "vitest/config";

/**
 * 테스트 2종
 * - server: Server Actions·Spring 호출·BFF 프록시. Node 환경, Spring API는 MSW로 대체
 * - components: 클라이언트 컴포넌트. jsdom 환경, Server Action은 테스트용 함수로 대체
 * async Server Component 화면은 Vitest 미지원(Next.js 문서)으로 대상 제외
 */
export default defineConfig({
  resolve: { tsconfigPaths: true },
  test: {
    restoreMocks: true,
    // npm run test:coverage. 수치 기준으로 실패시키지 않음 (보고서로 테스트가 닿지 않는 곳을 찾는 용도)
    // 제외: 테스트 자체, shadcn/ui 생성 컴포넌트, async Server Component 화면(page·layout, Vitest 미지원이라 E2E가 확인)
    coverage: {
      provider: "v8",
      include: ["app/**/*.{ts,tsx}", "components/**/*.tsx", "lib/**/*.ts", "proxy.ts"],
      exclude: ["**/*.test.{ts,tsx}", "components/ui/**", "app/**/page.tsx", "app/**/layout.tsx"],
      reporter: ["text-summary", "html", "json-summary"],
    },
    projects: [
      {
        extends: true,
        test: {
          name: "server",
          environment: "node",
          include: ["lib/**/*.test.ts", "app/**/*.test.ts", "proxy.test.ts"],
          setupFiles: ["test/setup-server.ts"],
        },
      },
      {
        extends: true,
        test: {
          name: "components",
          environment: "jsdom",
          include: ["components/**/*.test.tsx"],
          setupFiles: ["test/setup-dom.ts"],
        },
      },
    ],
  },
});
