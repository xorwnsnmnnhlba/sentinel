import { defineConfig, globalIgnores } from "eslint/config";
import nextVitals from "eslint-config-next/core-web-vitals";
import nextTs from "eslint-config-next/typescript";
import prettier from "eslint-config-prettier/flat";

const eslintConfig = defineConfig([
  ...nextVitals,
  ...nextTs,
  // 함수형만 사용. 클래스 컴포넌트·클래스 선언 금지 (오류 객체도 팩토리 함수로 생성, lib/api/server.ts apiError)
  {
    rules: {
      "no-restricted-syntax": [
        "error",
        { selector: "ClassDeclaration", message: "클래스 대신 함수와 타입을 사용합니다" },
        { selector: "ClassExpression", message: "클래스 대신 함수와 타입을 사용합니다" },
      ],
    },
  },
  // 서식 규칙은 Prettier 담당. ESLint의 서식 규칙 비활성화 (마지막에 위치)
  prettier,
  // Override default ignores of eslint-config-next.
  globalIgnores([
    // Default ignores of eslint-config-next:
    ".next/**",
    "out/**",
    "build/**",
    "next-env.d.ts",
  ]),
]);

export default eslintConfig;
