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
| 글꼴 | Pretendard 1.3.9 (동적 서브셋 자체 배포), Geist Mono (`next/font`) |
| 클래스 유틸 | `cn` 0.3.0 (shadcn 공식, clsx + tailwind-merge 대체), class-variance-authority 0.7.1 |
| TanStack Query | 5.103.1 |
| ESLint | 9.39.5 (`eslint-config-next` 16.3.5, `eslint-config-prettier` 10.1.8) |
| Prettier | 3.9.8 (`prettier-plugin-tailwindcss` 0.8.1) |
| Vitest | 5.0.1 (Vite 8.3.0, jsdom 30.1.0) |
| MSW | 2.15.0 |
| Playwright | 1.63.0 (chromium), 접근성 검사 `@axe-core/playwright` 4.13.0 |
| Testing Library | React 16.3.3, DOM 10.4.2, user-event 14.6.7, jest-dom 7.0.1 |
| Node.js | 로컬 24.21.0, Docker `node:24-alpine` (Next.js 요구사항 20.9.0 이상) |

`package-lock.json` 설치 버전 기준

## 디자인

색·글꼴·배치·버튼 배치·테마 규칙은 [DESIGN.md](DESIGN.md). Sentry 제품 화면 참고

- 테마: 시스템 설정(기본)·라이트·다크. 사이드바 아래 버튼으로 고르며 쿠키(`sentinel_theme`)로 유지, 서버가 `<html data-theme>`로 렌더링

## 구조

```
app/
 ├─ layout.tsx              공통 레이아웃 (화면 틀, TanStack Query Provider)
 ├─ providers.tsx           클라이언트 Provider, 문서의 CSP nonce를 Radix 스타일 삽입에 전달
 ├─ page.tsx                홈 대시보드 (실행 현황 요약, 스위트별 통과율, 최근 실패, 응답시간 악화)
 ├─ error.tsx               오류 화면 (retry()로 재조회)
 ├─ runs/
 │   ├─ (list)/page.tsx     실행 이력 목록 (조건 필터, 페이지 이동)
 │   ├─ (list)/loading.tsx  목록 전용 로딩 화면
 │   └─ [id]/               결과 상세, 미존재 시 404
 ├─ projects/
 │   ├─ page.tsx            프로젝트 목록·등록
 │   └─ [id]/               프로젝트 상세 (환경·스위트 등록·수정·삭제), 미존재 시 404
 ├─ suites/[id]/            스위트 상세 (즉시 실행, 케이스 목록·삭제·문서 가져오기, 응답시간), 미존재 시 404
 ├─ cases/
 │   ├─ new/                케이스 등록 (?suiteId=), 스위트 미존재 시 404
 │   └─ [id]/               케이스 수정, 미존재 시 404
 └─ api/[...path]/route.ts  브라우저용 BFF 프록시 (/api/** → Spring /api/**)
components/
 ├─ ui/                     shadcn/ui 컴포넌트 (CLI로 추가, 직접 수정 가능)
 ├─ catalog/                "⋯" 메뉴(action-menu), 등록·수정 다이얼로그, 삭제 확인, 변수·헤더·시크릿 편집기, 실행 폼, 케이스 편집기, 케이스 실행 순서 편집, 스케줄 입력·전환, 케이스 가져오기 창
 ├─ runs/                   실행 이력 필터 (GET 폼, 조건이 주소에 남음), 결과 상세의 케이스별 내용
 ├─ dashboard/              대시보드 요약 타일(이전 기간 대비 변화), 통과율 막대
 ├─ response-times/         응답시간 일별 추세 차트 (p50·p95 선, 툴팁, 표 보기)
 ├─ run-badges.tsx          판정·상태·주체 배지
 ├─ app-shell.tsx           화면 틀. 로그인 상태는 사이드바 + 본문, 로그인 전은 짙은 바탕 가운데 카드
 ├─ sidebar-nav.tsx         사이드바 메뉴(현재 화면 강조), 좁은 화면(768px 미만)의 메뉴 서랍
 ├─ theme-toggle.tsx        테마 선택 (시스템 설정·라이트·다크)
 └─ page-header.tsx         화면 머리 (상위 화면 링크·제목·설명, 오른쪽 버튼 영역)
test/                       테스트 공통 (MSW 서버, 환경 설정, redirect·notFound 확인, "⋯" 메뉴 열기)
lib/
 ├─ actions/catalog.ts      Server Actions (등록·수정·삭제·실행 → Spring 호출 후 refresh·redirect)
 ├─ actions/case-import.ts  Server Actions (문서 미리보기, 케이스 가져오기)
 ├─ actions/theme.ts        Server Action (테마 쿠키 저장)
 ├─ api/server.ts           Spring 호출 (서버 전용, server-only)
 ├─ api/types.ts            API 응답 타입
 ├─ case-draft.ts           케이스 폼 상태 ↔ API 요청 변환·검증 (화면과 Server Action 공용)
 ├─ extraction.ts           결과의 판정 결과에서 값 추출 결과 분리
 ├─ change-log.ts           변경 이력 표시 문구(대상·동작·바뀐 항목)와 대상·이력 화면 주소
 ├─ copy-name.ts            복제본 이름 ("원본 (복사)", 겹치면 번호, 최대 길이 맞춤)
 ├─ case-import.ts          가져오기 후보 선택·이름 검사, 요청 형식 확인 (화면과 Server Action 공용)
 ├─ format.ts               KST 시각·소요 시간·JSON 본문 표시
 ├─ dashboard.ts            대시보드 합계·이전 기간·변화 표시 계산
 ├─ response-times.ts       응답시간 기간(KST)·눈금·선 경로 계산
 ├─ csp.ts                  콘텐츠 보안 정책(CSP) 문자열, 요청별 nonce
 ├─ theme.ts                테마 값 해석 (알 수 없는 값은 system), theme-cookie.ts는 요청 쿠키 읽기 (서버 전용)
 └─ utils.ts
proxy.ts                    모든 화면 요청의 앞단 (로그인 확인, CSP 발급)
```

## 화면

| 경로 | 내용 |
|------|------|
| `/` | 홈 대시보드. 기간별 실행 수·통과율·실패 실행과 이전 기간 대비 변화, 스위트별 통과율, 최근 실패, 응답시간 악화 |
| `/login` | 로그인. 세션이 없으면 모든 화면이 이 주소로 이동 (`?from=`으로 원래 주소 유지) |
| `/settings/password` | 본인 비밀번호 변경. 변경 후 전체 로그아웃 |
| `/users` | 사용자 목록·초대 링크 발급·재설정 링크 발급·삭제 (관리자) |
| `/invites/{token}` | 초대 수락. 이름·비밀번호 설정 후 바로 로그인 (로그인 불필요) |
| `/password-resets/{token}` | 새 비밀번호 설정 후 로그인 화면으로 (로그인 불필요) |
| `/change-logs` | 변경 이력. 누가·언제·어떤 설정을 바꿨는지 최신순 20건 단위, 프로젝트·대상 종류 필터, 바뀐 항목 이름(값은 표시하지 않음). 항목 "⋯" 메뉴·케이스 수정 화면의 "변경 이력"은 그 항목만(`?targetType=&targetId=`), 프로젝트 메뉴는 프로젝트 전체(`?projectId=`) |
| `/runs` | 실행 이력. 최신순 20건 단위, 프로젝트·스위트·환경·상태·기간·실패만 필터, 통과·실패·오류 건수, 수동 실행은 실행한 사람 표시. 케이스 단건 실행·여기까지 실행은 대상 케이스 표시 |
| `/runs/{id}` | 결과 상세. 진행 중이면 "실행 중 · N건 중 M건 완료"와 끝난 케이스를 1.5초마다 새로 고쳐 표시하고 끝나면 멈춤 (실패 케이스는 끝난 뒤 펼침). 진행 중에는 "중지"(확인 창, 진행 중이던 케이스까지 기록), 중지된 실행은 중지한 사람과 "중지됨 · N건 중 M건 실행". 케이스별 판정(실패·오류는 펼침), 판정 규칙 결과, 값 추출 결과(가림 지정 값은 "가림"), 요청·응답 헤더와 본문(JSON 정렬). 보관기간이 지나 원문을 삭제한 실행은 안내 문구 표시. 여기까지 실행은 "여기까지" 배지와 마지막 케이스. 스위트 전체 실행은 직전 실행 링크와 새로 실패·복구·계속 실패·새 케이스 수, 케이스 행에 변화 배지. 앞 케이스가 추출하는 값이 비어 실행하지 못한 케이스는 해결 방법 안내 (단건 실행은 "여기까지 실행") |
| `/projects` | 프로젝트 목록, 새 프로젝트 등록(등록 후 상세로 이동) |
| `/projects/{id}` | 프로젝트 수정·삭제. 환경(기본 URL, 변수, 시크릿)과 스위트 추가·수정·삭제. 환경 복제는 원본의 기본 URL·변수를 채운 등록 창 (시크릿은 이름만 채우고 값 재입력) |
| `/suites/{id}` | 스위트 수정·삭제, 환경 선택 후 즉시 실행(완료 후 결과 상세로 이동), 스케줄·케이스 목록(실행 순서)과 편집, 케이스 실행 순서 변경(케이스 2건 이상), 케이스 "⋯" 메뉴(실행·여기까지 실행(첫 케이스 제외)·수정·복제·변경 이력·삭제), OpenAPI·Postman 문서에서 케이스 가져오기, 케이스별 응답시간과 일별 추세 |
| `/cases/new?suiteId={id}` | 케이스 등록. 요청 정의(메서드, 경로, 헤더, 본문)와 판정 규칙 5종, 값 추출, 저장 후 스위트 화면으로 이동. "등록 후 실행"은 등록하고 이 케이스만 실행한 뒤 결과 화면으로 이동 |
| `/cases/{id}` | 케이스 수정. 저장 시 요청 정의·판정 규칙·값 추출 전체 교체. "저장 후 실행"은 저장하고 이 케이스만 실행, "저장 후 여기까지 실행"은 스위트 처음부터 이 케이스까지 실행 (첫 케이스 제외, 환경이 여럿이면 선택) |

- 시각은 서버·브라우저 시간대와 무관하게 KST(`YYYY-MM-DD HH:mm:ss`)로 표시
- 삭제는 소프트 삭제. 확인 창에 함께 보관되는 하위 항목 표시, 실행 이력은 유지
- 시크릿(토큰·API 키): 환경 등록·수정 창에서 이름별로 추가·삭제. 값은 암호화 저장되어 다시 볼 수 없고, 목록에는 이름만 표시
  - 값 변경은 같은 이름으로 다시 입력. 삭제는 저장 시 반영되며 저장 전까지 취소 가능
  - 실행 이력에는 `****`로 표시 (서버에서 마스킹 후 저장)
  - 서버에 암호화 키가 없으면 저장이 거부되고 "시크릿 기능이 꺼져 있습니다" 안내 표시
- 스케줄: 스위트 화면에서 등록·cron 수정·활성 전환·삭제. cron은 5자리(분 시 일 월 요일), 서버 설정 시간대(기본 Asia/Seoul) 기준
  - 활성 전환은 즉시 반영. 활성화하면 현재 시각 기준으로 다음 실행 시각 재계산, 비활성화하면 다음 실행 시각 제거
  - 수정 창에서 실행 환경 변경 가능. 같은 프로젝트의 환경만 선택되며 지난 실행 이력은 그대로 유지
- 케이스 편집
  - 저장 전 화면에서 검증(`lib/case-draft.ts`), Server Action이 같은 규칙으로 재검사 후 Spring 저장. 규칙은 Spring 도메인 검증과 일치
  - 오류는 항목별 문구로 한 번에 표시 (예: `판정 규칙 2 (본문 포함): 포함할 문자열을 입력해 주세요`)
  - 경로는 `/` 또는 `{{변수}}`로 시작 (변수는 실행 시 치환 결과가 `/`로 시작해야 함)
  - JsonPath 기대값은 JSON으로 해석: `1000`·`true`는 숫자·불리언, 문자열 숫자는 `"1000"`처럼 따옴표. 그 외 입력은 문자열
  - 수정 화면은 JSON으로 읽히는 문자열 기대값을 따옴표 포함으로 표시하여 저장해도 값 유지
  - 값 추출: 변수 이름, 위치(본문 JSON 값·응답 헤더), JsonPath·헤더 이름, 값 가리기. 추출한 값은 같은 스위트 실행에서 뒤 케이스가 `{{변수}}`로 사용 (`api/README.md` "응답 값 추출")
    - 위치를 바꾸면 입력하지 않은 예시 값(`$.`, `Location`)만 새 위치의 예시로 교체
    - 스위트 화면 케이스 목록의 요청 아래에 추출하는 변수 표시. 복제 시 추출 규칙도 복사
- 케이스 복제: 요청 정의·판정 규칙·값 추출을 복사해 원본 바로 다음 실행 순서로 등록하고 새 케이스 수정 화면으로 이동
    - 결과 화면은 판정 결과 끝에 붙어 오는 추출 결과(`extract {{이름}} from ...`)를 판정 규칙 표와 나누어 "값 추출" 표로 표시 (`lib/extraction.ts`)
- 케이스 실행 순서 (스위트 화면 `순서 변경`)
  - 위·아래 버튼으로 한 칸씩 이동 후 저장. 목록·스위트 실행·값 추출 전달이 이 순서. 새 케이스는 마지막
  - 옮긴 케이스의 버튼에 초점 유지 (끝에 닿으면 반대 방향 버튼), 바뀐 위치를 화면 낭독기에 안내
  - 창을 연 뒤 다른 곳에서 케이스가 추가·삭제되면 저장하지 않고 안내. 다시 열면 최신 목록
- 케이스 가져오기 (스위트 화면 `문서 가져오기`)
  - 형식(OpenAPI 3 JSON·YAML, Postman 컬렉션 v2 JSON) 선택 후 문서를 붙여넣거나 파일 선택 → 미리보기 → 후보 선택·이름 수정 → 가져오기. 변환 규칙은 `api/README.md` "케이스 가져오기"
  - 미리보기에 문서 제목·버전, 서버 주소(환경 기본 URL 참고용), 변환 경고 표시. 스위트에 이미 있는 이름의 후보는 선택 해제 상태로 시작
  - 선택한 후보의 빈 이름·기존 이름·목록 안 중복은 화면에서 표시하고 가져오기 버튼을 막음. 가져오기는 전부 등록되거나 전부 등록되지 않음
  - 문서는 5MB(문자 수)까지, 한 번에 500건까지 (Spring 한도와 동일)
- 응답시간 (스위트 화면 하단)
  - 기간(최근 7·30·90일)·환경을 한 줄 조건으로 고르면 표와 차트 모두에 적용. 조건은 주소(`?days=&environmentId=&caseId=`)에 남음
  - 환경을 지정하지 않으면 이 스위트의 최근 실행 환경, 케이스를 지정하지 않으면 기간 중 p95가 가장 큰 케이스의 추세 표시
  - 표: 케이스별 건수·p50·p95·최대. 케이스 이름을 누르면 그 케이스의 일별 추세
  - 차트: p50·p95 두 선과 범례, 선 끝 라벨. 결과가 없는 날은 선을 끊고, 앞뒤가 빈 하루는 점으로 표시
  - 포인터·키보드(←→, Home·End, Esc)로 날짜를 고르면 그날의 p50·p95·최대·건수 표시. `표로 보기`로 같은 값을 표로 확인
  - 계열 색은 `globals.css`의 `--chart-series-1`(p50)·`--chart-series-2`(p95). 배경 대비·색각 이상 구분을 검증한 값이며 글자는 계열 색 대신 텍스트 색 사용
- 홈 대시보드 (`/`)
  - 기간(최근 7·30일, KST)을 고르면 화면 전체에 적용, 주소(`?days=`)에 남음
  - 요약 타일: 실행 수, 통과율, 실패·오류가 있는 실행 수와 이전 같은 길이 기간 대비 변화. 모두 보관되지 않은 스위트 기준이라 서로 어긋나지 않음
  - 변화는 좋아짐·나빠짐에 따라 색을 달리하되 화살표·문구를 함께 표시. 실행 수처럼 좋고 나쁨이 없는 지표는 회색, 이전 기간에 실행이 없으면 비교 생략
  - 스위트별 통과율: 통과율 낮은 순, 결과가 없는 스위트는 뒤. 통과율은 스위트 평균이 아닌 결과 수 기준. 좁은 화면에서는 결과·최근 실행 열 숨김
  - 최근 실패·오류 5건 (`모두 보기`는 실행 이력의 `실패·오류만` 조건), 응답시간이 나빠진 케이스 5건 (누르면 스위트 화면의 해당 케이스 추세)
- 실행 이력 조건에 `실패·오류만` 추가 (`?failing=true`)
- 로딩 화면은 목록에만 적용(라우트 그룹 `(list)`). 상세까지 감싸면 `notFound()`가 200 스트리밍 이후 렌더링되어 404 상태 코드 유실

## 인증

- 세션 토큰은 Next.js가 httpOnly 쿠키(`sentinel_session`)로 보관. 브라우저 스크립트에서 읽을 수 없고 Spring에는 `Authorization: Bearer` 헤더로 전달
- `proxy.ts`(Next.js 16에서 `middleware` 대체)가 쿠키 없는 요청을 로그인 화면으로 보냄. 토큰 유효성 판단은 Spring 몫. 로그인·초대·재설정 화면은 예외
- 조회 중 401이면 오류 화면 대신 로그인 화면으로 이동 (세션 만료)
- 로그인 실패 문구는 서버와 동일하게 사유를 구분하지 않음
- 로그인 후 이동 주소(`from`)는 `/`로 시작하는 내부 경로만 허용 (외부 주소로 유도 방지)
- 초대·재설정 링크는 발급 화면에서 한 번만 표시되며 복사해 직접 전달 (메일 발송 없음). 링크 주소는 요청 헤더의 호스트 기준
- 링크 만료·사용·위조는 서버가 구분하지 않으므로 화면 문구도 하나로 유지
- 관리자 메뉴(`사용자`)는 역할이 ADMIN일 때만 표시. 접근 통제는 화면이 아니라 Spring이 수행

## 콘텐츠 보안 정책 (CSP)

`proxy.ts`가 화면 요청마다 새 nonce(16바이트 난수)로 `Content-Security-Policy`를 만들어 요청·응답 헤더에 설정. Next.js가 요청 헤더에서 nonce를 읽어 프레임워크 스크립트·스타일에 부여 (모든 화면이 동적 렌더링이므로 가능)

| 지시어 | 값 | 이유 |
|--------|----|------|
| `script-src` | `'self' 'nonce-…' 'strict-dynamic'` | nonce 없는 인라인 스크립트·이벤트 속성(`onerror=` 등) 차단. XSS가 생겨도 스크립트 실행 불가 |
| `style-src-elem` | `'self' 'nonce-…'` | `<style>` 태그도 nonce 필요 |
| `style-src-attr` | `'unsafe-inline'` | `style=""` 속성은 nonce로 허용할 수 없음. 통과율 막대·응답시간 차트·Radix 위치 계산이 사용 |
| `img-src` | `'self' blob: data:` | |
| `connect-src` | `'self'` | 브라우저는 같은 출처(BFF)만 호출 |
| `object-src`·`frame-ancestors` | `'none'` | 플러그인 차단, 다른 사이트의 iframe 삽입 차단 |
| `base-uri`·`form-action` | `'self'` | `<base>` 변조, 외부로 폼 전송 차단 |

- 개발 서버(`next dev`)는 `script-src`에 `'unsafe-eval'`, 스타일에 `'unsafe-inline'`, `connect-src`에 `ws:`·`wss:`(HMR) 추가. 운영 빌드에는 없음
- `upgrade-insecure-requests` 미사용: `http://localhost:3000` 직접 접속 시 자원 요청이 HTTPS로 바뀌어 화면이 깨짐. HTTPS 강제는 Caddy가 담당
- Radix 대화상자의 스크롤 잠금이 넣는 `<style>`은 `get-nonce`로 nonce를 받음. 서버 렌더링마다 nonce가 바뀌므로(`router.refresh` 등) 처음 받은 문서의 스크립트에서 읽어 설정 (`app/providers.tsx`)
- 링크 미리 불러오기(`next-router-prefetch`) 요청은 문서가 아니므로 CSP 생략. 로그인 확인은 동일하게 적용
- 외부 스크립트·폰트·이미지를 추가하면 정책도 함께 수정. 위반은 E2E 공통 확인에서 실패로 드러남

## 데이터 조회 원칙

| 위치 | 방법 |
|------|------|
| Server Component | `lib/api/server.ts`로 Spring 직접 호출. 자체 Route Handler 경유 금지 (Next.js 권장, 불필요한 HTTP 왕복) |
| Client Component | TanStack Query로 `/api/**` 호출 → BFF 프록시가 Spring으로 전달. 주기적 갱신·상호작용이 필요한 경우만 사용 |
| 등록·수정·삭제·실행 | Server Actions(`lib/actions`)에서 Spring 호출 후 `refresh()`(현재 화면 재조회) 또는 `redirect` |

- Server Actions 사용 이유: Next.js 16 권장 방식. 변경과 화면 갱신이 한 번의 왕복으로 처리되고 Spring 주소가 서버에만 존재
- Server Action은 화면 없이 직접 POST로도 호출 가능하므로 바인딩된 ID·변수 JSON 형식 재검사. 값 검증의 최종 판단은 Spring
- Spring 오류는 화면용 문구로 변환: 400 입력값 확인(Spring 상세 포함), 404 대상 없음, 409 이름 중복, 그 외 고정 문구
  - 케이스 가져오기는 어느 문서·케이스가 문제인지 알 수 있도록 409도 Spring 상세 포함
- 폼은 `onSubmit`으로 제출. `action` prop 제출은 React가 제출 후 폼을 초기화하여 오류 시 입력값 유실

- 캐시 미사용 (`cache: "no-store"`). 실행 결과는 항상 최신 조회
- BFF 프록시는 조회(GET) 전용. 쓰기는 출처 확인이 있는 Server Action으로만 처리하여 쿠키만으로 인증되는 쓰기 경로를 두지 않음 (다른 메서드는 405)
- BFF 프록시는 응답 헤더 중 `Content-Type`·`Location`만 전달, Spring 장애 시 내부 정보 없는 502 반환
- 로그인 후 돌아갈 주소(`?from=`)는 같은 사이트 경로만 허용 (`lib/redirect-path.ts`). 브라우저가 `\`를 `/`로 바꾸고 탭·줄바꿈을 지워 해석하므로 문자열 앞부분이 아닌 실제 해석 결과로 판단
- 로그인이 반복 실패로 잠기면(429) 서버의 `Retry-After`로 남은 분을 안내
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
| `npm run e2e:up` | E2E 스택 기동 (PostgreSQL·api·web, 최초 실행 시 이미지 빌드) |
| `npm run e2e` | E2E 테스트 실행 |
| `npm run e2e:down` | E2E 스택 정리 (DB 데이터 삭제) |

컨테이너 실행은 루트에서 `docker compose up -d --build --wait` (api 기동 완료 후 web 기동)

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
- "⋯" 메뉴(Radix DropdownMenu)는 jsdom에서 포인터 클릭으로 테스트 파일의 두 번째 테스트부터 열리지 않음. `test/menu.ts`의 `openMenu`(키보드)로 열기

### E2E (Playwright)

실제 스택(PostgreSQL·api·web) 대상. 설정은 `playwright.config.ts`, 테스트는 `e2e/`

```bash
npm run e2e:up && npm run e2e && npm run e2e:down
```

- 스택은 `docker-compose.e2e.yml`. 개발/운영 스택과 분리 (HTTPS 앞단 13443·HTTP 13080, api 18080, DB는 컨테이너 내부 전용)
  - 테스트는 운영과 같은 HTTPS 앞단(`proxy/Caddyfile`)을 거쳐 접속. 내부 인증서라 Playwright에서 신뢰 검사 생략(`ignoreHTTPSErrors`)
  - `e2e/https.spec.ts`: HTTP→HTTPS 이동, 보안 헤더, 앞단을 거친 로그인과 Secure 쿠키
- 모든 테스트는 `e2e/fixtures.ts`의 `test`를 사용. 테스트 중 CSP 위반(`securitypolicyviolation`)이 1건이라도 있으면 실패
  - `e2e/csp.spec.ts`: 요청별 nonce, 모든 스크립트의 nonce, 주입된 인라인 이벤트 속성 차단
  - 공통 위치 지정: `mainMenu`(사이드바 주요 메뉴), `sidebar`(계정 영역 포함), `chooseAction`("⋯" 메뉴 열고 항목 선택)
- `e2e/layout.spec.ts`: 넓은 화면 사이드바·현재 메뉴 강조, 좁은 화면 메뉴 서랍·가로 스크롤 없음, 로그인 전 화면
- `e2e/accessibility.spec.ts`: 접근성(axe-core, WCAG 2.2 A·AA와 권장 규칙)을 라이트·다크 모두에서 주요 화면·열린 메뉴·대화상자·메뉴 서랍 대상으로 검사. 통과·실패·오류가 섞인 실행을 API로 만들어 배지·차트까지 포함
- 진행 중인 결과 화면은 `accessibilityViolations`가 끝나지 않음 (애니메이션이 끝나길 기다리는데 진행 표시의 회전 아이콘은 계속 돔). 접근성 검사는 끝난 실행 화면에서
- 실행은 백그라운드에서 진행되므로 API로 실행을 만들고 결과를 이어서 쓰는 준비 단계는 `waitForRun`(`e2e/fixtures.ts`)으로 완료를 기다림. 화면에서 실행하면 결과 화면의 새로 고침으로 완료까지 표시
- `e2e/run-change.spec.ts`: 첫 실행 뒤 한 케이스의 판정 규칙을 바꿔 다시 실행하면 직전 실행 링크·"새로 실패 1"·케이스 배지(펼친 상태), 첫 실행은 비교 없음 안내
- `e2e/change-log.spec.ts`: 케이스 이름 수정 → 케이스 "⋯" 메뉴의 변경 이력에 변경한 사람·"이름" 표시, 환경 변수 수정 → 프로젝트 필터로 "변수 userId"만 표시 (값은 화면에 없음)
- `e2e/case-order.spec.ts`: 순서 변경 창에서 키보드로 옮기고 저장하면 목록과 스위트 실행 결과가 같은 순서, 창을 연 뒤 케이스가 추가되면 저장하지 않고 안내
- `e2e/extraction.spec.ts`: 로그인 케이스가 응답의 세션 토큰을 추출(가림)하고 뒤 케이스가 그 토큰으로 인증하여 조회. 결과 화면의 추출 표와 응답 본문 가림 확인. 토큰을 쓰는 케이스만 실행하면 안내 표시, "여기까지 실행"이면 로그인과 함께 통과
- `e2e/theme.spec.ts`: 운영체제 설정 따르기, 고른 테마 우선·새로 고침 후 유지(서버 렌더링), 다크에서 실행 환경 선택 글자 대비
- 흐름: 프로젝트·환경(시크릿 포함) 등록 → 스위트·케이스 등록 → 즉시 실행·결과 확인(시크릿 마스킹) → 스케줄 등록·전환·수정·삭제 → 프로젝트 삭제 후 이력 유지 확인, 404 확인
- 케이스가 api 자신(`http://e2e-api:8080`)을 호출하므로 외부 서버 없이 실행 결과까지 검증
- 스택을 내리지 않고 반복 실행해도 되도록 프로젝트·스위트 이름에 실행 시각 포함
- `role="alert"` 선택 시 Next.js 라우트 알림(`#__next-route-announcer__`) 제외 필요

#### 느린 모드와 타이밍

로컬은 응답이 빨라 화면 전환 전에 누르는 실수가 드러나지 않음 (2026-09-26 CI에서만 실패한 사례: 케이스 화면으로 가는 링크를 누른 직후 "실행"을 찾아 이전 화면의 "지금 실행"이 눌림)

```bash
E2E_SLOW_MS=1000 npm run e2e
```

- `E2E_SLOW_MS`: 앱 요청(화면 문서, 화면 전환 데이터, Server Action)마다 지연(ms). 정적 파일 제외. 지정 시 테스트·`expect` 제한 시간도 늘어남
- CI는 항상 100ms로 실행. 새 흐름을 작성하면 로컬에서 1000ms로 한 번 확인
- 작성 원칙
  - 화면을 옮기는 동작(링크, 제출) 다음에는 새 화면의 제목이나 주소를 확인한 뒤 다음 동작
  - 이름이 다른 버튼의 일부가 될 수 있으면 `exact: true` (예: "실행"과 "지금 실행", "링크 발급"과 "초대 링크 발급")
  - 대화상자 안의 버튼은 대화상자 이름으로 범위를 좁혀 선택

## 코드 작성 원칙

- 서식은 Prettier, 코드 규칙은 ESLint 담당. ESLint의 서식 규칙은 `eslint-config-prettier`로 비활성화
  - 설정: `.prettierrc.json` (한 줄 120자, Tailwind 클래스 자동 정렬), 제외 대상 `.prettierignore`
  - `components/ui`(shadcn CLI 생성 파일)와 Markdown 문서는 서식 적용 제외
  - `next build`는 ESLint를 실행하지 않으므로 커밋 전 `npm run lint`, `npm run format:check` 실행
- IntelliJ 저장 시 자동 적용
  - **Settings → Languages & Frameworks → JavaScript → Prettier**: Automatic Prettier configuration, Run on save
  - **Settings → Languages & Frameworks → JavaScript → Code Quality Tools → ESLint**: Automatic ESLint configuration, Run eslint --fix on save

- 함수형만 사용. 클래스 선언·클래스 컴포넌트 금지 (ESLint `no-restricted-syntax`로 차단)
- `<form>`에는 `method` 필수 (ESLint `no-restricted-syntax`로 차단). `onSubmit` 폼은 `method="post"`, 조회 폼은 `method="get"`
  - `onSubmit`만 있는 폼은 JS 로드 전·실패 시 브라우저 기본 제출(GET)로 비밀번호 등 입력값이 주소(방문 기록·접속 로그·Referer)에 노출
  - JS 없이 로그인 폼을 제출해도 비밀번호가 주소에 남지 않는지 E2E(`e2e/auth.spec.ts`)로 확인
- 오류 객체는 팩토리 함수와 타입 가드로 처리: `apiError(status, detail)` 생성, `isApiError(error)` 판별 (`lib/api/server.ts`). `instanceof` 대신 타입 가드 사용

## 유의사항

- Next.js 16은 이전 버전과 API·규칙 차이 존재. 코드 작성 전 `node_modules/next/dist/docs/` 가이드 확인 (AI 에이전트용 규칙은 [AGENTS.md](AGENTS.md))
- 보안 릴리스 이슈로 Next.js 16.3.3 이상 유지
- `cacheComponents` 미사용 (기존 캐싱 모델). 캐시가 필요해지면 도입 검토
- Server Action 본문 한도를 `next.config.ts`의 `serverActions.bodySizeLimit`으로 6MB로 늘림 (기본 1MB, 케이스 가져오기의 문서 전송용). 모든 Server Action에 적용
- Server Action 인자 목록에 긴 문자열을 바로 넣지 않음. React가 인자 목록 안 문자열 길이를 배열 크기 한도(100만)에 합산하여 1MB 안팎에서 `Maximum array nesting exceeded`로 거부. 긴 값은 객체 속성으로 전달 (예: `previewCaseImport(suiteId, { content })`)

설계 배경은 [docs/design.md](../docs/design.md) 참고
