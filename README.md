# Sentinel

API 검증의 정시 자동 실행 및 결과 이력 공유 도구

> LLM 기반 에이전트가 아닌, 등록된 테스트 케이스의 실행·판정 자동화 러너

## 구조

```
[브라우저] → Next.js (:3000, BFF) → Spring Boot (:8080) → GW → 내부 모듈
                                          ↓
                        PostgreSQL (sentinel 스키마, 로컬 또는 RDS)
```

```
sentinel/
 ├─ api/                  Spring Boot 백엔드 (러너, 스케줄러, API)
 ├─ web/                  Next.js 프론트엔드 (BFF, 화면)
 ├─ docs/                 설계 문서
 ├─ docker-compose.yml    컨테이너 실행 구성 (api :8080, web :3000)
 ├─ sentinel.env.example  컨테이너 환경변수 템플릿
 └─ sentinel.env          컨테이너 환경변수 (git 제외, 직접 생성)
```

## 스택

| 모듈 | 구분 | 버전 | 문서 |
|------|------|------|------|
| `api` | Java | 25 (로컬 Azul Zulu 25.0.4.1) | [api/README.md](api/README.md) |
| | Spring Boot | 4.1.0 (Spring Framework 7.0.8) | |
| | Hibernate ORM | 7.4.1.Final | |
| | Lombok | 1.18.46 (`@Getter`, `@RequiredArgsConstructor`, `@NoArgsConstructor`만 사용) | |
| | PostgreSQL JDBC | 42.7.11 | |
| | Gradle | 9.7.1 | |
| `web` | Next.js | 16.3.5 | [web/README.md](web/README.md) |
| | React | 19.2.8 | |
| | TypeScript | 5.9.3 | |
| | Tailwind CSS | 4.3.3 | |
| | shadcn/ui | CLI 4.21.0 (Radix UI) | |
| | TanStack Query | 5.103.1 | |
| | Node.js | 24.21.0 (요구사항 20.9.0 이상) | |

모듈별 전체 의존성 버전은 각 README 참고

## 빠른 시작

### 1. 사전 준비
- Docker (컨테이너 실행, 테스트)
- PostgreSQL: 로컬 `localhost:5432`의 `sentinel` DB(기본값) 또는 개발기 RDS
  - `sentinel` 스키마와 테이블은 기동 시 Flyway가 생성
- 로컬 개발 시: JDK 25, Node.js 20.9.0 이상

### 2. 컨테이너 실행 (api, web)

```bash
cp sentinel.env.example sentinel.env
```

`sentinel.env`의 `DB_PASSWORD` 입력 후 실행. 값에 따옴표 사용 금지(`--env-file`은 따옴표까지 값으로 인식)

```bash
docker compose up -d --build
```

```bash
docker compose logs -f sentinel-api
```

- 로그의 `Started SentinelApplication` 확인 후 사용. `docker ps`에 `Restarting` 표시 시 설정 오류
- 화면: [http://localhost:3000](http://localhost:3000), API: `localhost:8080`
- 컨테이너 안의 `localhost`는 컨테이너 자신이므로 호스트 PostgreSQL은 `host.docker.internal`로 접속 (템플릿 기본값)
- RDS 사용 시 `sentinel.env`의 `DB_URL`·`DB_USERNAME`·`DB_PASSWORD`를 RDS 값으로 변경

### 3. 샘플 케이스 등록·실행

[docs/sample-cases.sql](docs/sample-cases.sql)로 자체 점검 스위트(케이스 4건) 등록

```bash
docker exec -i postgres psql -U postgres -d sentinel -v ON_ERROR_STOP=1 < docs/sample-cases.sql
```

```bash
curl -s -X POST localhost:8080/api/runs -H 'Content-Type: application/json' -d '{"suiteId": 1, "environmentId": 1}'
```

- 빈 DB 기준 스위트·환경 ID는 1. 그 외에는 파일 안의 ID 조회 쿼리 사용
- 결과 `summary`: 통과 2, 실패 1, 오류 1 (실패 알림 확인용 의도적 실패 2건 포함)
- 스케줄 등록, GW 케이스 템플릿: 파일 안 주석 참고

### 4. 로컬 개발 실행

```bash
# api (:8080). 기본값 localhost:5432/sentinel, 사용자 postgres
cd api
DB_PASSWORD=<비밀번호> ./gradlew bootRun
```

```bash
# web (:3000)
cd web
npm install
npm run dev
```

IDE 설정은 모듈별 README 참고

## 문서

- [설계 정리](docs/design.md): 아키텍처, 도메인 모델, 러너 및 스케줄 설계, 실행 환경, 구현 순서, 미결 사항
- [브랜치 규칙](docs/branch-convention.md): 브랜치 전략, 브랜치 이름, 커밋 메시지, 운영 원칙, 머지 기준
- [샘플 케이스 SQL](docs/sample-cases.sql): 자체 점검 스위트(판정 규칙 5종, 의도적 실패 2건), 실행·스케줄 명령, GW 케이스 템플릿
