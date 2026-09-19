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
    projects: [
      {
        extends: true,
        test: {
          name: "server",
          environment: "node",
          include: ["lib/**/*.test.ts", "app/**/*.test.ts"],
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
