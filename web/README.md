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
| ESLint | 9.39.5 (`eslint-config-next` 16.3.5) |
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
 └─ api/[...path]/route.ts  브라우저용 BFF 프록시 (/api/** → Spring /api/**)
components/
 ├─ ui/                     shadcn/ui 컴포넌트 (CLI로 추가, 직접 수정 가능)
 ├─ catalog/                등록·수정 다이얼로그, 삭제 확인, 변수 편집기, 실행 폼
 ├─ run-badges.tsx          판정·상태·주체 배지
 └─ site-header.tsx
lib/
 ├─ actions/catalog.ts      Server Actions (등록·수정·삭제·실행 → Spring 호출 후 refresh·redirect)
 ├─ api/server.ts           Spring 호출 (서버 전용, server-only)
 ├─ api/types.ts            API 응답 타입
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

- 시각은 서버·브라우저 시간대와 무관하게 KST(`YYYY-MM-DD HH:mm:ss`)로 표시
- 삭제는 소프트 삭제. 확인 창에 함께 보관되는 하위 항목 표시, 실행 이력은 유지
- 케이스 등록·편집 화면은 다음 단계
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

컨테이너 실행은 루트에서 `docker compose up -d --build` (api와 함께 기동)

## shadcn/ui 컴포넌트 추가

```bash
npx shadcn@latest add dialog
```

`components/ui`에 소스 파일로 추가되므로 직접 수정 가능. 설정은 `components.json`

## 유의사항

- Next.js 16은 이전 버전과 API·규칙 차이 존재. 코드 작성 전 `node_modules/next/dist/docs/` 가이드 확인 (AI 에이전트용 규칙은 [AGENTS.md](AGENTS.md))
- 보안 릴리스 이슈로 Next.js 16.3.3 이상 유지
- `cacheComponents` 미사용 (기존 캐싱 모델). 캐시가 필요해지면 도입 검토

설계 배경은 [docs/design.md](../docs/design.md) 참고
