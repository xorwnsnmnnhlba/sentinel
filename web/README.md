# sentinel-web

Sentinel 프론트엔드. 화면 및 BFF(Backend for Frontend) 담당  
브라우저는 Next.js만 호출, Spring API 주소는 서버에만 존재

```
[브라우저] → Next.js (:3000) → sentinel-api (:8080)
```

## 스택

| 구분 | 버전 |
|------|------|
| Next.js | 16.3.5 (App Router, Turbopack) |
| React / React DOM | 19.2.8 |
| TypeScript | 5.9.3 |
| Tailwind CSS | 4.3.3 (`@tailwindcss/postcss` 4.3.3, `tw-animate-css` 1.4.0) |
| shadcn/ui | CLI 4.21.0, 스타일 `radix-nova` (Radix UI 기반, `radix-ui` 1.6.7) |
| 아이콘 | lucide-react 1.47.0 |
| 클래스 유틸 | `cn` 0.3.0 (shadcn 공식, clsx + tailwind-merge 대체), class-variance-authority 0.7.1 |
| TanStack Query | 5.103.1 |
| ESLint | 9.39.5 (`eslint-config-next` 16.3.5, `eslint-config-prettier` 10.1.8) |
| Prettier | 3.9.8 (`prettier-plugin-tailwindcss` 0.8.1) |
| Vitest | 5.0.1 (Vite 8.3.0, jsdom 30.1.0) |
| MSW | 2.15.0 |
| Testing Library | React 16.3.3, DOM 10.4.2, user-event 14.6.7, jest-dom 7.0.1 |
| Node.js | 로컬 24.21.0, Docker `node:24-alpine` (Next.js 요구사항 20.9.0 이상) |

`package-lock.json` 설치 버전 기준

## 구조

```
app/
 ├─ layout.tsx              공통 레이아웃 (헤더, TanStack Query Provider)
 ├─ providers.tsx           클라이언트 Provider
 ├─ page.tsx                홈
 ├─ error.tsx               오류 화면 (retry()로 재조회)
 ├─ runs/
 │   ├─ (list)/page.tsx     실행 이력 목록 (스위트 필터, 페이지 이동)
 │   ├─ (list)/loading.tsx  목록 전용 로딩 화면
 │   └─ [id]/               결과 상세, 미존재 시 404
 ├─ projects/
 │   ├─ page.tsx            프로젝트 목록·등록
 │   └─ [id]/               프로젝트 상세 (환경·스위트 등록·수정·삭제), 미존재 시 404
 ├─ suites/[id]/            스위트 상세 (즉시 실행, 케이스 목록·삭제), 미존재 시 404
 ├─ cases/
 │   ├─ new/                케이스 등록 (?suiteId=), 스위트 미존재 시 404
 │   └─ [id]/               케이스 수정, 미존재 시 404
 └─ api/[...path]/route.ts  브라우저용 BFF 프록시 (/api/** → Spring /api/**)
components/
 ├─ ui/                     shadcn/ui 컴포넌트 (CLI로 추가, 직접 수정 가능)
 ├─ catalog/                등록·수정 다이얼로그, 삭제 확인, 변수·헤더 편집기, 실행 폼, 케이스 편집기
 ├─ run-badges.tsx          판정·상태·주체 배지
 └─ site-header.tsx
test/                       테스트 공통 (MSW 서버, 환경 설정, redirect·notFound 확인)
lib/
 ├─ actions/catalog.ts      Server Actions (등록·수정·삭제·실행 → Spring 호출 후 refresh·redirect)
 ├─ api/server.ts           Spring 호출 (서버 전용, server-only)
 ├─ api/types.ts            API 응답 타입
 ├─ case-draft.ts           케이스 폼 상태 ↔ API 요청 변환·검증 (화면과 Server Action 공용)
 ├─ format.ts               KST 시각·소요 시간·JSON 본문 표시
 └─ utils.ts
```

## 화면

| 경로 | 내용 |
|------|------|
| `/runs` | 실행 이력. 최신순 20건 단위, 스위트 필터(`?suiteId=`), 통과·실패·오류 건수 |
| `/runs/{id}` | 결과 상세. 케이스별 판정(실패·오류는 펼침), 판정 규칙 결과, 요청·응답 헤더와 본문(JSON 정렬) |
| `/projects` | 프로젝트 목록, 새 프로젝트 등록(등록 후 상세로 이동) |
| `/projects/{id}` | 프로젝트 수정·삭제. 환경(기본 URL, 변수 키·값)과 스위트 추가·수정·삭제 |
| `/suites/{id}` | 스위트 수정·삭제, 환경 선택 후 즉시 실행(완료 후 결과 상세로 이동), 케이스 목록·삭제 |
| `/cases/new?suiteId={id}` | 케이스 등록. 요청 정의(메서드, 경로, 헤더, 본문)와 판정 규칙 5종, 저장 후 스위트 화면으로 이동 |
| `/cases/{id}` | 케이스 수정. 저장 시 요청 정의·판정 규칙 전체 교체 |

- 시각은 서버·브라우저 시간대와 무관하게 KST(`YYYY-MM-DD HH:mm:ss`)로 표시
- 삭제는 소프트 삭제. 확인 창에 함께 보관되는 하위 항목 표시, 실행 이력은 유지
- 케이스 편집
  - 저장 전 화면에서 검증(`lib/case-draft.ts`), Server Action이 같은 규칙으로 재검사 후 Spring 저장. 규칙은 Spring 도메인 검증과 일치
  - 오류는 항목별 문구로 한 번에 표시 (예: `판정 규칙 2 (본문 포함): 포함할 문자열을 입력해 주세요`)
  - 경로는 `/` 또는 `{{변수}}`로 시작 (변수는 실행 시 치환 결과가 `/`로 시작해야 함)
  - JsonPath 기대값은 JSON으로 해석: `1000`·`true`는 숫자·불리언, 문자열 숫자는 `"1000"`처럼 따옴표. 그 외 입력은 문자열
  - 수정 화면은 JSON으로 읽히는 문자열 기대값을 따옴표 포함으로 표시하여 저장해도 값 유지
- 로딩 화면은 목록에만 적용(라우트 그룹 `(list)`). 상세까지 감싸면 `notFound()`가 200 스트리밍 이후 렌더링되어 404 상태 코드 유실

## 데이터 조회 원칙

| 위치 | 방법 |
|------|------|
| Server Component | `lib/api/server.ts`로 Spring 직접 호출. 자체 Route Handler 경유 금지 (Next.js 권장, 불필요한 HTTP 왕복) |
| Client Component | TanStack Query로 `/api/**` 호출 → BFF 프록시가 Spring으로 전달. 주기적 갱신·상호작용이 필요한 경우만 사용 |
| 등록·수정·삭제·실행 | Server Actions(`lib/actions`)에서 Spring 호출 후 `refresh()`(현재 화면 재조회) 또는 `redirect` |

- Server Actions 사용 이유: Next.js 16 권장 방식. 변경과 화면 갱신이 한 번의 왕복으로 처리되고 Spring 주소가 서버에만 존재
- Server Action은 화면 없이 직접 POST로도 호출 가능하므로 바인딩된 ID·변수 JSON 형식 재검사. 값 검증의 최종 판단은 Spring
- Spring 오류는 화면용 문구로 변환: 400 입력값 확인(Spring 상세 포함), 404 대상 없음, 409 이름 중복, 그 외 고정 문구
- 폼은 `onSubmit`으로 제출. `action` prop 제출은 React가 제출 후 폼을 초기화하여 오류 시 입력값 유실

- 캐시 미사용 (`cache: "no-store"`). 실행 결과는 항상 최신 조회
- BFF 프록시는 응답 헤더 중 `Content-Type`·`Location`만 전달, Spring 장애 시 내부 정보 없는 502 반환
- `lib/api/server.ts`는 `server-only`로 보호. 클라이언트 컴포넌트에서 import 시 빌드 실패

## 환경변수

| 이름 | 기본값 | 설명 |
|------|--------|------|
| `INTERNAL_API_URL` | `http://localhost:8080` | Spring API 주소 (서버 전용). Docker에서는 `http://sentinel-api:8080` |

- `NEXT_PUBLIC_` 접두어 사용 금지. 사용 시 브라우저 번들에 내부 주소 노출
- 로컬에서 기본값과 다를 때만 `web/.env.local`에 지정 (`.env*`는 git 제외)

## 실행

```bash
npm install
npm run dev
```

[http://localhost:3000](http://localhost:3000)에서 확인. api가 `localhost:8080`에서 실행 중이어야 데이터 조회 가능

| 명령 | 설명 |
|------|------|
| `npm run dev` | 개발 서버 |
| `npm run build` | 프로덕션 빌드 (`output: "standalone"`) |
| `npm run lint` | ESLint |
| `npm run format` | Prettier로 서식 적용 |
| `npm run format:check` | 서식 검사 (변경 없음) |
| `npm test` | 테스트 1회 실행 |
| `npm run test:watch` | 테스트 감시 모드 |

컨테이너 실행은 루트에서 `docker compose up -d --build` (api와 함께 기동)

## shadcn/ui 컴포넌트 추가

```bash
npx shadcn@latest add dialog
```

`components/ui`에 소스 파일로 추가되므로 직접 수정 가능. 설정은 `components.json`

## 테스트

Vitest + MSW + React Testing Library. 설정은 `vitest.config.mts`

| 구분 | 환경 | 대상 | 방법 |
|------|------|------|------|
| server | Node | `lib/**/*.test.ts`, `app/**/*.test.ts` | Spring API를 MSW(`test/msw.ts`)로 대체. 요청 본문, 오류 문구 변환, `refresh`·`redirect` 검증 |
| components | jsdom | `components/**/*.test.tsx` | Server Action을 테스트용 함수로 대체, 사용자 조작(user-event)으로 검증 |

- 테스트 파일은 대상 파일 옆에 `*.test.ts(x)`로 작성
- MSW는 처리하지 않은 요청을 실패로 처리(`onUnhandledRequest: "error"`). 테스트마다 `server.use()`로 필요한 응답만 지정
- `redirect()`·`notFound()`는 throw된 오류의 digest로 확인 (`test/next.ts`)
- async Server Component 화면(`page.tsx`)은 Vitest 미지원(Next.js 문서)으로 제외. 추후 E2E로 검증
- Radix UI 목록(Select 등)은 jsdom에 레이아웃이 없어 접근성 트리에서 숨김으로 판정. 키보드로 열고 목록 안 텍스트로 선택

## 코드 작성 원칙

- 서식은 Prettier, 코드 규칙은 ESLint 담당. ESLint의 서식 규칙은 `eslint-config-prettier`로 비활성화
  - 설정: `.prettierrc.json` (한 줄 120자, Tailwind 클래스 자동 정렬), 제외 대상 `.prettierignore`
  - `components/ui`(shadcn CLI 생성 파일)와 Markdown 문서는 서식 적용 제외
  - `next build`는 ESLint를 실행하지 않으므로 커밋 전 `npm run lint`, `npm run format:check` 실행
- IntelliJ 저장 시 자동 적용
  - **Settings → Languages & Frameworks → JavaScript → Prettier**: Automatic Prettier configuration, Run on save
  - **Settings → Languages & Frameworks → JavaScript → Code Quality Tools → ESLint**: Automatic ESLint configuration, Run eslint --fix on save

- 함수형만 사용. 클래스 선언·클래스 컴포넌트 금지 (ESLint `no-restricted-syntax`로 차단)
- 오류 객체는 팩토리 함수와 타입 가드로 처리: `apiError(status, detail)` 생성, `isApiError(error)` 판별 (`lib/api/server.ts`). `instanceof` 대신 타입 가드 사용

## 유의사항

- Next.js 16은 이전 버전과 API·규칙 차이 존재. 코드 작성 전 `node_modules/next/dist/docs/` 가이드 확인 (AI 에이전트용 규칙은 [AGENTS.md](AGENTS.md))
- 보안 릴리스 이슈로 Next.js 16.3.3 이상 유지
- `cacheComponents` 미사용 (기존 캐싱 모델). 캐시가 필요해지면 도입 검토

설계 배경은 [docs/design.md](../docs/design.md) 참고
