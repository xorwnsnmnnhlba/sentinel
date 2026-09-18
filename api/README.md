# sentinel-api

Sentinel 백엔드. 테스트 케이스 실행(러너), 스케줄 실행, 결과 저장, web(BFF) 대상 API 담당

## 스택

| 구분 | 버전 |
|------|------|
| Java | 25 (로컬 Azul Zulu 25.0.4.1) |
| Gradle | 9.7.1 (Wrapper) |
| Spring Boot | 4.1.0 |
| Spring Framework | 7.0.8 |
| Spring Data JPA | 4.1.0 |
| Hibernate ORM | 7.4.1.Final |
| Jakarta Persistence | 3.2.0 |
| Hibernate Validator | 9.1.0.Final |
| Jackson | 3.1.4 |
| Tomcat (내장) | 11.0.22 |
| HikariCP | 7.0.2 |
| PostgreSQL JDBC | 42.7.11 |
| DB | PostgreSQL 18, 개발기 AWS RDS `sentinel` 스키마 |
| Testcontainers | 2.0.5 |

`./gradlew dependencies --configuration runtimeClasspath` 기준

## 환경변수

`src/main/resources/application.yml`의 DB 접속 정보는 환경변수로 주입. 값은 루트 `sentinel.env`에 보관

| 이름 | 설명 | 예시 |
|------|------|------|
| `DB_URL` | JDBC URL | `jdbc:postgresql://<rds-endpoint>:5432/<db>?currentSchema=sentinel` |
| `DB_USERNAME` | DB 계정 (`sentinel` 스키마 한정 권한 계정) | `sentinel_app` |
| `DB_PASSWORD` | DB 비밀번호 | |

미설정 시 `Could not resolve placeholder 'DB_URL'` 오류로 기동 실패

## 실행

### 터미널

```bash
set -a; source ../sentinel.env; set +a
./gradlew bootRun
```

로그의 `Tomcat started on port 8080` 출력으로 정상 기동 확인

### IntelliJ

루트 `sentinel` 폴더를 하나의 프로젝트로 열고 `api`를 Gradle 프로젝트로 연결하는 방식

1. 루트 `sentinel` 폴더 열기
2. **Gradle** 탭 **+** → `api/build.gradle` 선택하여 연결. 루트에는 Gradle 빌드가 없으므로 루트 경로 연결 금지
3. **Settings → Build, Execution, Deployment → Build Tools → Gradle**
   - Gradle JVM: JDK 25
4. **File → Project Structure → Project**
   - SDK: JDK 25, Language level: 25
5. **Run → Edit Configurations → SentinelApplication**
   - JDK 25, 클래스패스 모듈 `sentinel.main`
   - Main class: `com.sentinel.SentinelApplication`
   - Environment variables: `sentinel.env` 불러오기 또는 직접 입력

`build.gradle` 수정 후에는 🐘 **Load Gradle Changes** 실행 필수. 미실행 시 새 의존성 import에서 `Cannot resolve symbol` 발생

## 빌드 및 테스트

```bash
./gradlew build     # 컴파일 + 테스트 + jar 생성
./gradlew test      # 테스트만 실행
```

테스트는 Testcontainers 기반이므로 Docker 실행 필요. DB 환경변수·RDS 접속 불필요

| 컨테이너 | 이미지 | 용도 |
|----------|--------|------|
| PostgreSQL | `postgres:18-alpine` | 스프링 컨텍스트·JPA 테스트 (`TestcontainersConfiguration`) |
| WireMock | `wiremock/wiremock:3.13.2` | `RequestExecutor` 통합 테스트. 스텁은 `src/test/resources/wiremock/mappings` |

### RDS 없이 로컬 실행

```bash
./gradlew bootTestRun
```

`TestSentinelApplication`이 PostgreSQL 컨테이너를 띄워 연결. 종료 시 데이터 소멸

## 케이스 1건 실행 확인

케이스 등록 API·화면이 없는 단계이므로 SQL로 등록 후 API로 실행

### 1. 케이스 등록 (SQL)

```sql
INSERT INTO sentinel.project (name, created_at, updated_at) VALUES ('sample', now(), now());
INSERT INTO sentinel.environment (project_id, name, base_url, variables, created_at, updated_at)
  VALUES (1, 'dev', 'https://<gw-host>', '{"userId": "42"}', now(), now());
INSERT INTO sentinel.test_suite (project_id, name, created_at, updated_at) VALUES (1, 'smoke', now(), now());
INSERT INTO sentinel.test_case (suite_id, name, method, path, headers, body, created_at, updated_at)
  VALUES (1, 'user lookup', 'GET', '/users/{{userId}}', '{"Accept": ["application/json"]}', NULL, now(), now());
```

- `bootTestRun` 사용 시 DB 접속: `docker ps`로 `postgres:18-alpine` 컨테이너 확인 후 `docker exec -it <컨테이너> psql -U test -d test`
- `path`는 `/` 시작 상대 경로. 경로·헤더 값·본문에 `{{변수}}` 사용 가능, `\{{`는 치환 없이 `{{` 출력
- `headers`는 `{"이름": ["값", ...]}` 형식

### 2. 실행 (API)

```bash
curl -i -X POST localhost:8080/api/test-cases/1/runs \
  -H 'Content-Type: application/json' -d '{"environmentId": 1}'
```

| API | 설명 |
|-----|------|
| `POST /api/test-cases/{id}/runs` | 케이스 1건 실행. 본문 `{"environmentId": 1}`. 201 + `Location: /api/test-runs/{runId}` |
| `POST /api/test-suites/{id}/runs` | 스위트 전체 실행. 케이스 ID 순 순차 실행, 실패 케이스가 있어도 나머지 계속 실행 |
| `GET /api/test-runs/{runId}` | 실행 결과 조회. `summary`에 전체·응답 수신·오류 건수 |

### 결과 해석

| 필드 | 의미 |
|------|------|
| `outcome: RESPONDED` | 응답 수신. 4xx·5xx 포함. 성공·실패 판정은 3단계(Assertion)에서 추가 |
| `outcome: ERROR` | 응답 미수신 또는 전송 전 실패. 사유는 `errorReason` |
| `errorReason` | `UNRESOLVED_VARIABLE`(변수 누락, 미전송) / `INVALID_REQUEST`(잘못된 URL·헤더) / `TIMEOUT` / `CONNECTION_FAILED` / `IO_ERROR` / `UNEXPECTED` |

- 저장되는 요청·응답 헤더 중 `Authorization`, `Proxy-Authorization`, `Cookie`, `Set-Cookie`, `X-Api-Key` 값은 `****`로 마스킹. 실제 전송 값은 원본 사용
- 응답 본문은 64KB(UTF-8 기준) 초과 시 잘라서 저장, `bodyTruncated: true` 표시
- 요청 URL의 변수 값은 인코딩하지 않음. 공백 등 URL에 쓸 수 없는 문자는 `INVALID_REQUEST`

## 패키지 구조 (계획)

```
com.sentinel
 ├─ domain/          엔티티, 도메인 서비스
 ├─ runner/          RequestExecutor, VariableResolver (Spring 의존성 없는 순수 자바)
 │   └─ assertion/   AssertionEvaluator + 구현체
 ├─ application/     TestRunService, SuiteService
 ├─ api/             Controller, DTO
 └─ infra/           Repository, 스케줄러, 암호화
```

설계 배경 및 유의사항은 [docs/design.md](../docs/design.md) 참고

## Spring Boot 4 유의사항

- autoconfigure의 목적별 소형 모듈 분리. 3.x 예제 의존성 복사 시 실패하므로 [start.spring.io](https://start.spring.io)에서 확인
- Jackson 3.x 사용, 패키지 경로 `tools.jackson`으로 변경
