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
        {
          // method가 없으면 JS 로드 전·실패 시 브라우저 기본 제출(GET)로 비밀번호 등 입력값이 주소에 노출
          selector: 'JSXOpeningElement[name.name="form"]:not(:has(JSXAttribute[name.name="method"]))',
          message: 'form에는 method를 지정합니다 (onSubmit 폼은 method="post", 조회 폼은 method="get")',
        },
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
    // 테스트 커버리지 보고서 (npm run test:coverage)
    "coverage/**",
  ]),
]);

export default eslintConfig;
