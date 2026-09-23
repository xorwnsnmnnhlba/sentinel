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
| Flyway | 12.4.0 |
| SnakeYAML Engine | 3.0.1 (케이스 가져오기의 OpenAPI 문서 해석, Boot 미관리라 버전 명시) |
| Testcontainers | 2.0.5 |
| Lombok | 1.18.46 (`@Getter`, `@RequiredArgsConstructor`, `@NoArgsConstructor(access = PROTECTED)`만 사용, `lombok.config`로 제한) |

`./gradlew dependencies --configuration runtimeClasspath` 기준

## 환경변수

`src/main/resources/application.yml`의 DB 접속 정보는 환경변수로 주입. 미설정 시 로컬 PostgreSQL 기본값 사용

| 이름 | 기본값 | RDS 예시 |
|------|--------|----------|
| `DB_URL` | `jdbc:postgresql://localhost:5432/sentinel?currentSchema=sentinel` | `jdbc:postgresql://<rds-endpoint>:5432/<db>?currentSchema=sentinel` |
| `DB_USERNAME` | `postgres` | `sentinel_app` (`sentinel` 스키마 한정 권한 계정) |
| `DB_PASSWORD` | (빈 값) | |

- 스키마·테이블은 기동 시 Flyway가 생성 (아래 "스키마 변경" 참고)
- DB 드라이버 타임아웃: `socketTimeout` 30초, `connectTimeout` 10초, `tcpKeepAlive` 사용
- 컨테이너 실행 시에는 루트 `sentinel.env` 사용 (`sentinel.env.example` 참고)

## 실행

### 터미널

```bash
DB_PASSWORD=<비밀번호> ./gradlew bootRun
```

`sentinel.env`의 `DB_URL`은 컨테이너용(`host.docker.internal`)이므로 로컬 실행에 불러오지 않음

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
   - Environment variables: `DB_PASSWORD=<비밀번호>` (RDS 사용 시 `DB_URL`, `DB_USERNAME` 추가)

Lombok은 IntelliJ 번들 플러그인으로 인식. **Settings → Build, Execution, Deployment → Compiler → Annotation Processors**에서 annotation processing 활성화 알림 표시 시 활성화

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

## 스키마 변경

Flyway 마이그레이션으로만 변경. 기동 시 `ddl-auto: validate`가 엔티티와 테이블 일치 여부 검증, 불일치 시 기동 실패

1. 엔티티 수정
2. `src/main/resources/db/migration/V{다음 번호}__{설명}.sql` 추가 (예: `V2__add_test_case_tags.sql`)
3. `./gradlew test`로 검증. 테스트 컨테이너에 마이그레이션 적용 후 `validate` 수행

- 적용된 마이그레이션 파일 수정 금지. Flyway가 체크섬 불일치로 기동 거부
- ANSI SQL 표준만 사용 (`docs/design.md` "SQL 작성 원칙"). JSON 값은 `JSON`, 긴 문자열은 `VARCHAR(n)`, 표준 예약어 컬럼명 금지
- 스키마 접두어(`sentinel.`) 미사용. Flyway `default-schema` 설정으로 지정
- 외래키 제약 미사용. 새 연관관계는 `@JoinColumn(foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))` 지정, 조회 컬럼에는 인덱스 추가
- 데이터가 있는 테이블에 NOT NULL 컬럼 추가 시 `DEFAULT` 지정 또는 값 채운 뒤 제약 추가
- 적용 이력: `sentinel.flyway_schema_history`

## 케이스 1건 실행 확인

케이스 등록 API·화면이 없는 단계이므로 SQL로 등록 후 API로 실행

### 1. 케이스 등록 (SQL)

```sql
INSERT INTO sentinel.project (name, created_at, updated_at)
VALUES ('sample', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO sentinel.environment (project_id, name, base_url, variables, created_at, updated_at)
VALUES ((SELECT id FROM sentinel.project WHERE name = 'sample' AND archived_at IS NULL),
        'dev', 'https://<gw-host>', '{"userId": "42"}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO sentinel.test_suite (project_id, name, created_at, updated_at)
VALUES ((SELECT id FROM sentinel.project WHERE name = 'sample' AND archived_at IS NULL), 'smoke', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO sentinel.test_case (suite_id, name, http_method, path, headers, body, assertions, created_at, updated_at)
VALUES ((SELECT s.id FROM sentinel.test_suite s JOIN sentinel.project p ON p.id = s.project_id
         WHERE p.name = 'sample' AND s.name = 'smoke'
         AND p.archived_at IS NULL AND s.archived_at IS NULL),
        'user lookup', 'GET', '/users/{{userId}}', '{"Accept": ["application/json"]}', NULL,
        '[{"type": "statusCode", "anyOf": ["200"]},
          {"type": "responseTime", "maxMillis": 2000},
          {"type": "jsonPath", "path": "$.id", "operator": "EQUALS", "expected": 42}]',
        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
```

- 표준 SQL만 사용 (`RETURNING`, `::` 캐스트, `now()` 등 PostgreSQL 전용 문법 미사용)
- 부모 ID는 유니크한 이름(프로젝트명, 프로젝트 내 스위트명)으로 조회. 이름 오타 시 `NOT NULL` 위반으로 실패
- 판정 규칙 5종·의도적 실패 케이스·GW 템플릿을 포함한 전체 예시: [docs/sample-cases.sql](../docs/sample-cases.sql)

- `bootTestRun` 사용 시 DB 접속: `docker ps`로 `postgres:18-alpine` 컨테이너 확인 후 `docker exec -it <컨테이너> psql -U test -d test`
- `path`는 `/` 또는 `{{변수}}`로 시작하는 상대 경로. 경로·헤더 값·본문에 `{{변수}}` 사용 가능, `\{{`는 치환 없이 `{{` 출력
  - 변수로 시작하는 경로(`{{apiPrefix}}/runs`)는 실행 시 치환 결과가 `/`로 시작해야 함. 아니면 전송 없이 `INVALID_REQUEST` (기본 URL 뒤에 붙어 다른 호스트로 전송되는 것 방지)
- `headers`는 `{"이름": ["값", ...]}` 형식
- `assertions`는 판정 규칙 배열. 생략(NULL·빈 배열) 시 기본 판정 `status in [2xx]` 적용

### 판정 규칙 (`assertions`)

| `type` | 속성 | 예 |
|--------|------|-----|
| `statusCode` | `anyOf`: 상태 코드 목록, `x` 와일드카드 | `{"type": "statusCode", "anyOf": ["200", "2xx"]}` |
| `responseTime` | `maxMillis`: 응답 시간 상한(포함) | `{"type": "responseTime", "maxMillis": 2000}` |
| `header` | `name`, `operator`, `value` | `{"type": "header", "name": "Content-Type", "operator": "CONTAINS", "value": "json"}` |
| `jsonPath` | `path`(JsonPath), `operator`, `expected`(JSON 값) | `{"type": "jsonPath", "path": "$.items.length()", "operator": "GREATER_THAN", "expected": 0}` |
| `bodyContains` | `text`: 포함 문자열(대소문자 구분) | `{"type": "bodyContains", "text": "OK"}` |

| `operator` | `header` | `jsonPath` | 의미 |
|------------|:--------:|:----------:|------|
| `EXISTS` / `NOT_EXISTS` | O | O | 존재 여부 |
| `EQUALS` / `NOT_EQUALS` | O | O | 일치. `jsonPath` 숫자는 값 비교(`1 == 1.0`), 문자열 `"1000"`과 숫자 `1000`은 불일치 |
| `CONTAINS` | O | O | 부분 문자열 포함 또는 배열 원소 포함 |
| `MATCHES` | O | O | 정규식 부분 일치. 문자열만 대상 |
| `GREATER_THAN` / `GREATER_THAN_OR_EQUAL` / `LESS_THAN` / `LESS_THAN_OR_EQUAL` | X | O | 숫자 비교. 숫자가 아니면 타입 불일치로 실패 |

- 규칙은 실패가 있어도 전부 평가하여 `assertionResults`에 규칙별 결과 기록
- HTML 오류 페이지 등 JSON 객체·배열이 아닌 본문은 `jsonPath` 규칙 실패(`response body is not JSON`)

### 2. 실행 (API)

```bash
curl -i -X POST localhost:8080/api/runs \
  -H 'Content-Type: application/json' -d '{"caseId": 1, "environmentId": 1}'
```

- 본문 `suiteId`(스위트 전체)와 `caseId`(케이스 1건) 중 정확히 하나 지정. 둘 다 없거나 둘 다 있으면 400
- 201 + `Location: /api/runs/{id}`, 본문은 실행 결과
- 스위트 실행은 케이스 ID 순 순차 실행, 실패 케이스가 있어도 나머지 계속 실행

### 결과 해석

| 필드 | 의미 |
|------|------|
| `verdict: PASSED` | 판정 규칙 전부 통과 |
| `verdict: FAILED` | 응답 수신 후 판정 규칙 실패. 규칙별 결과는 `assertionResults` (`description`, `passed`, `message`) |
| `verdict: ERROR` | 응답 미수신 또는 전송 전 실패. 사유는 `errorReason` |
| `outcome` | 응답 수신 여부. `RESPONDED`(4xx·5xx 포함) / `ERROR` |
| `errorReason` | `UNRESOLVED_VARIABLE`(변수 누락, 미전송) / `INVALID_REQUEST`(잘못된 URL·헤더) / `TIMEOUT` / `CONNECTION_FAILED` / `IO_ERROR` / `UNEXPECTED` |

- 저장되는 요청·응답 헤더 중 `Authorization`, `Proxy-Authorization`, `Cookie`, `Set-Cookie`, `X-Api-Key` 값은 `****`로 마스킹. 실제 전송 값은 원본 사용
- 응답 본문은 64KB(UTF-8 기준) 초과 시 잘라서 저장, `bodyTruncated: true` 표시
- 요청·응답 헤더와 본문은 보관기간(기본 30일) 경과 후 삭제 ("결과 원문 보관기간" 참고)
- 요청 URL의 변수 값은 인코딩하지 않음. 공백 등 URL에 쓸 수 없는 문자는 `INVALID_REQUEST`

## 인증

| API | 설명 |
|-----|------|
| `POST /api/sessions` | 로그인. 본문 `{"email": "...", "password": "..."}`. 201 + `{"token": "...", "user": {...}}` |
| `GET /api/sessions/current` | 현재 세션 사용자 |
| `DELETE /api/sessions/current` | 로그아웃. 세션 즉시 폐기 |

- 로그인 외 모든 `/api/**`는 `Authorization: Bearer {토큰}` 필요. 없으면 401 ProblemDetail
- 브라우저는 Next.js(BFF)의 httpOnly 쿠키를 사용하고, Spring은 쿠키를 모름 (토큰만 헤더로 수신)
- 비밀번호는 BCrypt 해시, 세션 토큰은 SHA-256 해시로만 저장. 로그인 실패 사유는 구분하지 않음(계정 존재 여부 비노출)
- 세션 만료는 로그인 시점 기준 고정 (`sentinel.auth.session-validity`, 기본 12h). 사용해도 연장되지 않음
### 사용자 관리

| API | 권한 | 설명 |
|-----|------|------|
| `GET /api/users` | 관리자 | 사용자 목록 |
| `POST /api/users/invites` | 관리자 | 초대 링크 발급. 본문 `{"email": "...", "role": "MEMBER"}`. 201 + `{"token": "...", "expiresAt": "..."}` |
| `POST /api/users/{id}/password-resets` | 관리자 | 재설정 링크 발급 |
| `DELETE /api/users/{id}` | 관리자 | 삭제(보관). 세션·남은 링크 정리 |
| `POST /api/users/me/password` | 본인 | 비밀번호 변경. 본문 `{"currentPassword": "...", "newPassword": "..."}` |
| `GET /api/invites/{token}` | 없음 | 초대 링크 확인 (대상 이메일) |
| `POST /api/invites/{token}` | 없음 | 초대 수락. 본문 `{"name": "...", "password": "..."}`. 201 + 세션 토큰 |
| `GET /api/password-resets/{token}` | 없음 | 재설정 링크 확인 |
| `POST /api/password-resets/{token}` | 없음 | 새 비밀번호 설정. 204 |

- 가입 경로는 초대뿐. 공개 가입·비밀번호 찾기 폼 없음 (메일 발송 미사용)
- 링크 토큰은 1회용이며 저장은 해시로만. 만료·사용·위조는 모두 404로 동일하게 응답
- 비밀번호는 10자 이상. 변경·재설정 시 해당 사용자의 모든 세션 종료
- 자기 자신 삭제와 마지막 관리자 삭제는 400으로 거부
- 링크 전달은 사람이 직접 (메신저 등). 링크 자체가 자격증명이므로 공개 채널 금지

| 설정 | 기본값 | 설명 |
|------|--------|------|
| `sentinel.auth.invite-validity` | `7d` | 초대 링크 유효 기간 |
| `sentinel.auth.password-reset-validity` | `2h` | 재설정 링크 유효 기간 |

| 설정 | 기본값 | 설명 |
|------|--------|------|
| `sentinel.auth.session-validity` | `12h` | 세션 유효 기간 |
| `sentinel.auth.admin-email` | 없음 (`SENTINEL_ADMIN_EMAIL`) | 최초 관리자 이메일. 사용자가 없을 때만 생성 |
| `sentinel.auth.admin-password` | 없음 (`SENTINEL_ADMIN_PASSWORD`) | 최초 관리자 비밀번호. 첫 로그인 후 변경 권장 |

## REST API

모든 리소스는 최상위 컬렉션, 관계는 쿼리 파라미터로 필터 (`docs/design.md` "REST API 설계")

| 리소스 | 목록 | 단건 | 생성·수정 | 삭제 |
|--------|------|------|-----------|------|
| 프로젝트 | `GET /api/projects` | `GET /api/projects/{id}` | `POST /api/projects`, `PATCH /api/projects/{id}` | `DELETE /api/projects/{id}` |
| 환경 | `GET /api/environments?projectId=` | `GET /api/environments/{id}` | `POST /api/environments`, `PATCH /api/environments/{id}` | `DELETE /api/environments/{id}` |
| 스위트 | `GET /api/suites?projectId=` | `GET /api/suites/{id}` | `POST /api/suites`, `PATCH /api/suites/{id}` | `DELETE /api/suites/{id}` |
| 케이스 | `GET /api/cases?suiteId=` | `GET /api/cases/{id}` | `POST /api/cases`, `PATCH /api/cases/{id}` | `DELETE /api/cases/{id}` |
| 실행 | `GET /api/runs?projectId=&suiteId=&environmentId=&status=&from=&to=&page=0&size=20` | `GET /api/runs/{id}` | `POST /api/runs` | |
| 스케줄 | `GET /api/schedules?suiteId=` | `GET /api/schedules/{id}` | `POST /api/schedules`, `PATCH /api/schedules/{id}` | `DELETE /api/schedules/{id}` |

- 필터 파라미터 생략 시 전체. 존재하지 않는 부모 ID로 필터링하면 빈 목록(200), 단건 조회만 미존재 시 404 ProblemDetail
- 응답 항목에 부모 ID 포함 (`environment.projectId`, `suite.projectId`, `case.suiteId`)
- 스위트는 `caseCount`, 케이스는 요청 정의(`request`)와 판정 규칙(`assertions`), 환경은 `baseUrl`과 변수 포함
- 실행 목록: 최신순, 페이지 응답 `{content, page, size, totalElements, totalPages}` (`page`는 0부터, `size`는 1~100으로 보정)
  - 행: 프로젝트·스위트·환경의 `{id, name}`, `trigger`, `status`, 시각, `summary`(전체·통과·실패·오류), `triggeredBy`
  - `triggeredBy`: 수동 실행을 시작한 사용자 `{id, name}`(이메일). 스케줄 실행과 이전 기록은 `null`
  - 필터: `projectId`, `suiteId`, `environmentId`, `status`(`RUNNING`/`COMPLETED`/`ABORTED`), 시작 시각 기간 `from`·`to`. 여러 개를 함께 지정하면 모두 만족하는 실행만
  - `from`·`to`는 `yyyy-MM-dd`이며 `sentinel.schedule.zone`(기본 `Asia/Seoul`) 기준. 시작일 00:00부터 종료일 다음 날 00:00 직전까지 (종료일 당일 포함)
  - 실행 건수와 무관하게 페이지당 쿼리 최대 4회 (이름 함께 로딩, 판정 건수 일괄 집계, 실행 주체 일괄 조회)
- 실행 상세: 스위트·환경·케이스 이름과 케이스별 결과, 수동 실행이면 실행 주체 포함
  - 실행 주체는 ID로 보관하고 이름은 조회 시 확인. 사용자를 삭제해도 이력에는 그대로 표시
- 등록·수정
  - 등록은 201 + `Location`, 본문은 단건 조회와 같은 형태. 수정은 200 + 수정 후 항목
  - `PATCH`는 부분 수정. 생략(null)한 필드는 유지, `description`은 빈 문자열로 제거
  - 환경 `variables`, 케이스 `request`·`assertions`는 지정 시 전체 교체. `assertions: []`는 기본 판정(2xx)으로 복귀
  - 같은 부모 아래 사용 중인 이름과 중복 시 409 (프로젝트는 전체에서 유일). 앞뒤 공백은 제거 후 비교
  - 잘못된 값(빈 이름, 길이 초과, 잘못된 `baseUrl`·경로·메서드·판정 규칙)은 400, 미존재·보관된 부모는 404
  - 부모 변경(다른 프로젝트·스위트로 이동)은 미지원

| 등록 | 본문 예시 |
|------|-----------|
| 프로젝트 | `{"name": "billing", "description": "결제"}` |
| 환경 | `{"projectId": 1, "name": "dev", "baseUrl": "https://gw.dev.internal", "variables": {"userId": "42"}, "secrets": {"token": "..."}}` |
| 스위트 | `{"projectId": 1, "name": "smoke", "description": null}` |
| 케이스 | `{"suiteId": 1, "name": "결제 내역 조회", "request": {"method": "GET", "path": "/billing/v1/history?userId={{userId}}", "headers": {"Accept": ["application/json"]}, "body": null}, "assertions": [{"type": "statusCode", "anyOf": ["200"]}]}` |

### 케이스 가져오기 (OpenAPI 3)

문서를 케이스 후보로 변환해 확인한 뒤, 고른 후보만 가져오는 2단계 방식

| API | 설명 |
|-----|------|
| `POST /api/case-import-previews` | 변환만 하고 저장하지 않음. 본문 `{"suiteId": 1, "format": "OPENAPI_3", "content": "<JSON 또는 YAML 원문>"}` |
| `POST /api/case-imports` | 한 트랜잭션으로 등록. 본문 `{"suiteId": 1, "cases": [{"name": "...", "request": {...}, "assertions": [...]}]}`. 201 + `{"suiteId", "cases": [등록한 케이스]}` |

- 미리보기 응답: `title`, `version`, `servers`(환경 기본 URL 설정 참고용), `cases`, `warnings`(문서 단위)
  - 후보: `source`(예: `GET /pets/{petId}`), `name`, `request`, `assertions`, `warnings`(생략·대체한 내용), `conflict`(스위트에 같은 이름 있음)
  - 후보를 그대로(또는 이름·요청을 고쳐) `cases`로 보내면 가져오기. `source`·`warnings`·`conflict`는 무시
- 가져오기는 한 건이라도 실패하면 전부 미등록. 이름 중복(스위트 기존 케이스, 목록 안 중복)은 409, 잘못된 케이스는 400 (`cases[1] 'Broken': ...`처럼 위치 표시)
- 문서는 5MB(문자 수)까지, 한 번에 가져오기는 500건까지
- Swagger 2.0 등 OpenAPI 3이 아닌 문서, 해석할 수 없는 문서, 작업이 없는 문서는 400

| OpenAPI | 케이스 |
|---------|--------|
| `paths` × 메서드 | 케이스 1건. 문서 순서 유지. `trace`는 미지원 메서드라 제외 (문서 경고) |
| 이름 | `summary` → `operationId` → `GET /pets/{petId}` 순. 문서 안 중복은 ` (2)`, ` (3)` 추가 |
| 경로 파라미터 `{petId}` | 변수 `{{petId}}`. 변수 이름에 쓸 수 없는 문자는 `_`로 바꾸고 경고 |
| 필수 query·header 파라미터 | `?status={{status}}`, `X-Request-Id: {{X-Request-Id}}`. 선택 파라미터는 생략, cookie는 미지원 경고 |
| 경로 공통 `parameters` | 작업에 병합. 같은 이름·위치는 작업 쪽 우선 |
| JSON 요청 본문 (`application/json`, `*+json`) | 미디어 타입 `example` → `examples`의 첫 값 → 스키마 `example` 순. 있으면 본문과 `Content-Type` 헤더, 없으면 빈 본문으로 경고 |
| 그 밖의 요청 본문 (`multipart/form-data` 등) | 빈 본문으로 경고 |
| 응답 코드 | 선언된 2xx(`200`, `201`, `2XX` → `2xx`)로 상태 코드 판정. 없으면 `2xx`로 경고 |
| `$ref` | 문서 내부 참조(`#/...`)만 해석. 외부 파일 참조·순환 참조는 경고 후 생략 |
| YAML 앵커·별칭 | 펼쳐서 해석. 별칭 폭증(billion laughs) 방지를 위해 별칭 50개까지 |
| `servers`, 보안 스킴 | 케이스에 미반영. 기본 URL은 환경에서, 인증 헤더는 변수·시크릿으로 직접 지정 |

### 환경 변수와 시크릿

| 구분 | 저장 | 조회 | 용도 |
|------|------|------|------|
| `variables` | 평문 | 값까지 조회 | 경로·헤더·본문 치환용 일반 값 (`userId`, `apiPrefix`) |
| `secrets` | AES-GCM 암호화 | 이름만 조회(`secretNames`) | 토큰·API 키 |

- 치환 방법은 동일 (`{{이름}}`). 변수와 시크릿에 같은 이름 사용 시 400
- 수정 시 `variables`는 전체 교체, `secrets`는 이름별 반영 (값 지정 시 등록·교체, `null`이면 삭제, 생략한 이름은 유지)
- 시크릿 값은 실행 이력에 저장할 때 `****`로 가림 (URL·헤더·본문·오류 메시지). 실제 전송에는 원래 값 사용
- 암호화 키(`sentinel.secret.key`, `SENTINEL_SECRET_KEY`) 미설정 시 시크릿 등록은 400으로 거부. 평문 저장 방지
  - Base64 16·24·32바이트. 생성 예: `openssl rand -base64 32`
  - 키를 바꾸면 기존 시크릿을 읽지 못하므로 해당 환경의 실행이 400으로 실패. 값을 다시 입력해야 함

- 삭제는 소프트 삭제(보관). 204 응답, 하위 항목 연쇄 보관, 실행 이력은 유지
  - 프로젝트 → 환경·스위트·케이스·스케줄, 환경 → 해당 스케줄, 스위트 → 케이스·스케줄
  - 보관된 항목은 목록에서 제외되고 단건 조회·재삭제·실행은 404. 같은 이름으로 새로 등록 가능

## 스케줄 실행

| API | 설명 |
|-----|------|
| `POST /api/schedules` | 스케줄 등록. 본문 `{"suiteId": 1, "environmentId": 1, "cron": "10 9 * * 1-5"}`. 201 + `Location` |
| `GET /api/schedules/{id}` | 조회. `nextRunAt`(다음 실행 시각), `lastTriggeredAt`(마지막 실행 시각) 포함 |
| `PATCH /api/schedules/{id}` | 부분 수정. `{"enabled": false}`는 `nextRunAt` 제거, `{"enabled": true}`는 현재 시각 기준 재계산. `{"cron": "30 17 * * 1-5"}`는 활성 상태면 `nextRunAt` 재계산. `{"environmentId": 2}`는 실행 환경 변경(스위트와 같은 프로젝트만, 다르면 400). 생략한 필드는 유지 |

- cron은 5자리 표준 형식(`분 시 일 월 요일`), `Asia/Seoul` 기준. 예: `10 9 * * 1-5` = 평일 09:10
- 60초 간격 폴링으로 실행 시각 도달 스케줄의 스위트 실행 (`trigger: SCHEDULED`)
- 노트북 절전 등으로 놓친 실행은 1회만 실행 후 다음 시각으로 이동
- 기동 시 `RUNNING`으로 남은 실행은 `ABORTED` 처리
- DB 접속 실패 시 해당 주기 건너뜀. 연결 실패 시 1회, 복구 시 1회 알림
- 실행 완료 시 INFO 로그 1줄 (수동·스케줄 공통)

```
Run 12 completed: trigger=SCHEDULED suite=3 total=4 passed=2 failed=1 errors=1 elapsed=1203ms
```

```bash
docker compose logs sentinel-api | grep "completed:"
```

| 설정 | 기본값 | 설명 |
|------|--------|------|
| `sentinel.schedule.enabled` | `true` | 폴링 동작 여부 |
| `sentinel.schedule.zone` | `Asia/Seoul` | cron 해석 시간대 |
| `sentinel.schedule.poll-interval` | `60s` | 폴링 주기 |

## 결과 원문 보관기간

실행 이력은 계속 보관하고, 용량이 큰 요청·응답 원문만 보관기간이 지나면 비움

| 비움 | 유지 |
|------|------|
| 요청·응답 헤더, 요청·응답 본문 | 실행(주체·시각·상태), 판정, 규칙별 결과, 오류 사유, 요청 메서드·URL, 상태 코드, 소요 시간 |

- 매일 03:30(`sentinel.schedule.zone` 기준) 실행 시작 시각이 보관기간보다 오래된 실행 대상. 진행 중(`RUNNING`)인 실행 제외
- 정리한 실행은 `raw_purged_at` 기록, 상세 응답의 `rawPurgedAt`으로 확인 (원문 보관 중이면 `null`)
- 실행 단위로 나누어 트랜잭션 처리. 도중 실패 시 정리한 분은 유지되고 다음 날 나머지 이어서 정리
- 정리 건수는 INFO 로그 1줄

```
Purged raw request/response of 1200 result(s) in 300 run(s) started before 2026-08-24T18:30:00Z
```

| 설정 | 기본값 | 설명 |
|------|--------|------|
| `sentinel.retention.enabled` | `true` | 정리 배치 동작 여부 |
| `sentinel.retention.raw-retention` | `30d` | 원문 보관 기간. 0 이하이면 기동 실패 |
| `sentinel.retention.cron` | `0 30 3 * * *` | 실행 시각 (Spring cron 6자리, 초 포함) |
| `sentinel.retention.batch-size` | `500` | 한 트랜잭션에서 정리할 실행 수 |

## 실패 알림

스케줄 실행에 판정 실패(`FAILED`)·오류(`ERROR`)가 있을 때만 발송. 성공·수동 실행은 미발송

```
[Sentinel] dev 결제 - 12건 중 2건 실패
POST   /billing/v1/charge → status in [2xx]: was 500
GET    /billing/v1/history → response time <= 2000ms: was 3200ms
결과: http://sentinel.example.com/runs/12
```

| 알림 | 발송 시점 |
|------|-----------|
| `[Sentinel] {환경} {스위트} - N건 중 M건 실패` | 스케줄 실행 결과에 실패·오류 존재 |
| `[Sentinel] 스케줄 {id} 실행 오류` | 스케줄 실행 자체가 예외로 중단 |
| `[Sentinel] DB 연결 실패 - 스케줄 실행 중단` | DB 접속 실패 첫 주기 1회 |
| `[Sentinel] DB 연결 복구 - 스케줄 실행 재개` | 접속 복구 시 1회, 중단 기간 포함 |

### 발송 채널

| 설정 | 기본값 | 설명 |
|------|--------|------|
| `sentinel.notification.webhook-url` | 없음 (`SENTINEL_WEBHOOK_URL`) | 수신 Webhook 주소. 미지정 시 WARN 로그로만 기록 |
| `sentinel.notification.web-base-url` | 없음 (`SENTINEL_WEB_URL`) | 결과 화면 주소. 지정 시 실패 알림 마지막 줄에 `결과: {주소}/runs/{id}` 추가 |
| `sentinel.notification.connect-timeout` | `3s` | 연결 제한 시간 |
| `sentinel.notification.read-timeout` | `5s` | 응답 대기 제한 시간 |

- 본문은 `{"text": "제목\n본문"}` 한 필드. Slack·Teams 수신 Webhook 주소를 그대로 사용 가능
- Webhook 주소는 자격증명이므로 환경변수로 주입하고 로그·git에 남기지 않음 (`sentinel.env`)
- 발송 실패(4xx·5xx, 타임아웃, 연결 실패)는 WARN 로그만 남기고 실행·폴링은 계속
- 다른 형식이 필요하면 `com.sentinel.application.notification.Notifier` 구현체 추가 후 `NotificationConfig`의 빈 교체

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
