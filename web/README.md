# sentinel-web

Sentinel 프론트엔드. 화면 및 BFF(Backend for Frontend) 담당  
브라우저는 Next.js만 호출, api(Spring Boot) 호출은 Next.js Route Handler에서 서버사이드로 수행

```
[브라우저] → Next.js (:3000) → sentinel-api (:8080)
```

## 스택

| 구분 | 버전 |
|------|------|
| Next.js | 16.3.5 (App Router) |
| React / React DOM | 19.2.8 |
| TypeScript | 5.9.3 |
| Tailwind CSS | 4.3.3 (`@tailwindcss/postcss` 4.3.3) |
| ESLint | 9.39.5 (`eslint-config-next` 16.3.5) |
| @types/node | 20.19.43 |
| @types/react | 19.3.0 |
| Node.js | 20.9.0 이상 (Next.js 16.3.5 요구사항) |

`package-lock.json` 설치 버전 기준

## 실행

```bash
npm install
npm run dev
```

[http://localhost:3000](http://localhost:3000)에서 확인

| 명령 | 설명 |
|------|------|
| `npm run dev` | 개발 서버 |
| `npm run build` | 프로덕션 빌드 |
| `npm run start` | 빌드 결과 실행 |
| `npm run lint` | ESLint |

## 환경변수 (계획)

로컬은 `web/.env.local`에 보관. `.env*` 파일 `.gitignore` 등록 완료

| 이름 | 설명 | 로컬 | Docker |
|------|------|------|--------|
| `INTERNAL_API_URL` | api 주소 (서버 전용) | `http://localhost:8080` | `http://sentinel-api:8080` |

- api 주소에 `NEXT_PUBLIC_` 접두어 사용 금지. 사용 시 브라우저 번들에 내부 주소 노출
- Docker 컨테이너 간 통신은 `localhost`가 아닌 컨테이너 이름 사용

## 유의사항

- Next.js 16은 이전 버전과 API·규칙 차이 존재. 코드 작성 전 `node_modules/next/dist/docs/` 가이드 확인 (AI 에이전트용 규칙은 [AGENTS.md](AGENTS.md))
- 보안 릴리스 이슈로 Next.js 16.3.3 이상 유지
- Docker 배포 시 `next.config.ts`에 `output: 'standalone'` 설정 예정

설계 배경은 [docs/design.md](../docs/design.md) 참고
