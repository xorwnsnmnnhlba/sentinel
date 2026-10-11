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
 ├─ scripts/              배포 전 점검(preflight.sh), 성능 점검(perf/)
 ├─ .github/workflows/    CI (PR·main 푸시마다 web lint·서식·테스트·빌드, api 테스트, E2E)
 ├─ docker-compose.e2e.yml E2E 테스트용 독립 스택 (PostgreSQL·api·web·HTTPS 앞단, 포트 13443·18080)
 ├─ docker-compose.yml    컨테이너 실행 구성 (HTTPS 앞단 :443, web·api는 127.0.0.1 :3000·:8080)
 ├─ proxy/Caddyfile       HTTPS 앞단 설정 (Caddy)
 ├─ .env.example          HTTPS 앞단 접속 주소·인증서 방식 템플릿 (.env는 git 제외)
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
- PostgreSQL: 로컬 `localhost:5432`의 `sentinel` DB(기본값) 또는 AWS RDS
  - `sentinel` 스키마와 테이블은 기동 시 Flyway가 생성
- 로컬 개발 시: JDK 25, Node.js 20.9.0 이상

### 2. 컨테이너 실행 (api, web, HTTPS 앞단)

```bash
cp sentinel.env.example sentinel.env
```

`sentinel.env`의 `DB_PASSWORD` 입력 후 실행. 값에 따옴표 사용 금지(`--env-file`은 따옴표까지 값으로 인식)

```bash
docker compose up -d --build --wait
```

- `--wait`는 api·web 헬스체크 통과(기동 완료)까지 대기 후 종료. 기동 실패 시 오류로 종료
- 기동 실패 시 원인은 로그로 확인. `docker ps`에 `Restarting` 또는 `unhealthy` 표시 시 설정 오류

```bash
docker compose logs -f sentinel-api
```

- 화면: [https://localhost](https://localhost) (HTTPS 앞단 경유). 같은 PC에서는 [http://localhost:3000](http://localhost:3000)도 가능
- API: `localhost:8080`. web·api 포트는 `127.0.0.1`에만 열려 있어 다른 PC에서는 접근 불가 (HTTPS 앞단만 네트워크에 공개)
- 컨테이너 안의 `localhost`는 컨테이너 자신이므로 호스트 PostgreSQL은 `host.docker.internal`로 접속 (템플릿 기본값)
- RDS 사용 시 `sentinel.env`의 `DB_URL`·`DB_USERNAME`·`DB_PASSWORD`를 RDS 값으로 변경

### HTTPS 앞단 (다른 PC에서 접속)

`sentinel-proxy`(Caddy)가 TLS를 종료하고 web으로 전달. HTTP(80)는 HTTPS(443)로 이동. 설정은 `proxy/Caddyfile`

```bash
cp .env.example .env
```

| 변수 (`.env`) | 기본값 | 설명 |
|------|--------|------|
| `SENTINEL_SITE_ADDRESS` | `localhost` | 접속 주소. 다른 PC가 쓰는 이름 또는 IP (내부 DNS·hosts에 등록된 이름). 쉼표로 여러 개 지정 가능 (예: `localhost, sentinel.team.internal`) |
| `SENTINEL_TLS` | `internal` | 인증서 방식. 아래 표 |
| `SENTINEL_HSTS_MAX_AGE` | `0` | HSTS 유지 시간(초). 0이면 미사용. 사용자 PC가 이미 신뢰하는 인증서(기관 발급·Let's Encrypt)일 때만 사용. localhost에는 보내지 않음 |

| `SENTINEL_TLS` | 용도 | 준비 |
|------|------|------|
| `internal` | 내부 주소 | Caddy 내부 인증 기관이 발급. 루트 인증서를 접속하는 PC마다 신뢰 등록 (아래) |
| 이메일 주소 | 공개 도메인 | Let's Encrypt 자동 발급·갱신. 80·443 포트가 인터넷에서 접근 가능해야 함 |
| `/certs/cert.pem /certs/key.pem` | 보유한 인증서 | `proxy/certs/`에 `cert.pem`·`key.pem` 배치 (git 제외) |

내부 인증 기관의 루트 인증서 내보내기

```bash
docker compose cp sentinel-proxy:/data/caddy/pki/authorities/local/root.crt ./sentinel-root.crt
```

- 받은 `sentinel-root.crt`를 접속하는 PC의 신뢰할 수 있는 루트 인증 기관에 등록 (macOS 키체인 접근 → 시스템 → 항상 신뢰, Windows 인증서 관리 → 신뢰할 수 있는 루트 인증 기관)
- 전달한 루트 인증서는 받는 쪽에서 SHA-256 지문을 비교해 변조 여부 확인 (`openssl x509 -in sentinel-root.crt -noout -fingerprint -sha256`)
- 등록 전에는 브라우저에 인증서 경고 표시. 경고를 넘기면 접속은 되나 매번 확인 필요
- 루트 인증서는 `caddy_data` 볼륨에 보관. `docker compose down -v`로 볼륨을 지우면 새로 만들어져 PC마다 다시 등록해야 함
- 세션 쿠키는 `Secure`라 HTTPS로만 전달. `http://<IP>:3000` 같은 평문 접속은 막혀 있고, 열더라도 로그인이 유지되지 않음
- `sentinel.env`의 `SENTINEL_WEB_URL`(실패 알림의 결과 링크)도 `https://<접속 주소>`로 지정

#### 서버 배포

서버 준비, 보유 인증서 배치, 배포 전 점검(`scripts/preflight.sh`), 업데이트·되돌리기, 인증서 교체, 백업은 [docs/deploy.md](docs/deploy.md)

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
