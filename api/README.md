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
| Testcontainers | 2.0.5 |

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
- 스키마 접두어(`sentinel.`) 미사용. Flyway `default-schema` 설정으로 지정
- 데이터가 있는 테이블에 NOT NULL 컬럼 추가 시 `DEFAULT` 지정 또는 값 채운 뒤 제약 추가
- 적용 이력: `sentinel.flyway_schema_history`

## 케이스 1건 실행 확인

케이스 등록 API·화면이 없는 단계이므로 SQL로 등록 후 API로 실행

### 1. 케이스 등록 (SQL)

```sql
INSERT INTO sentinel.project (name, created_at, updated_at) VALUES ('sample', now(), now());
INSERT INTO sentinel.environment (project_id, name, base_url, variables, created_at, updated_at)
  VALUES (1, 'dev', 'https://<gw-host>', '{"userId": "42"}', now(), now());
INSERT INTO sentinel.test_suite (project_id, name, created_at, updated_at) VALUES (1, 'smoke', now(), now());
INSERT INTO sentinel.test_case (suite_id, name, method, path, headers, body, assertions, created_at, updated_at)
  VALUES (1, 'user lookup', 'GET', '/users/{{userId}}', '{"Accept": ["application/json"]}', NULL,
          '[{"type": "statusCode", "anyOf": ["200"]},
            {"type": "responseTime", "maxMillis": 2000},
            {"type": "jsonPath", "path": "$.id", "operator": "EQUALS", "expected": 42}]',
          now(), now());
```

- `bootTestRun` 사용 시 DB 접속: `docker ps`로 `postgres:18-alpine` 컨테이너 확인 후 `docker exec -it <컨테이너> psql -U test -d test`
- `path`는 `/` 시작 상대 경로. 경로·헤더 값·본문에 `{{변수}}` 사용 가능, `\{{`는 치환 없이 `{{` 출력
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
curl -i -X POST localhost:8080/api/test-cases/1/runs \
  -H 'Content-Type: application/json' -d '{"environmentId": 1}'
```

| API | 설명 |
|-----|------|
| `POST /api/test-cases/{id}/runs` | 케이스 1건 실행. 본문 `{"environmentId": 1}`. 201 + `Location: /api/test-runs/{runId}` |
| `POST /api/test-suites/{id}/runs` | 스위트 전체 실행. 케이스 ID 순 순차 실행, 실패 케이스가 있어도 나머지 계속 실행 |
| `GET /api/test-runs/{runId}` | 실행 결과 조회. `summary`에 전체·통과·실패·오류 건수 |

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
- 요청 URL의 변수 값은 인코딩하지 않음. 공백 등 URL에 쓸 수 없는 문자는 `INVALID_REQUEST`

## 스케줄 실행

| API | 설명 |
|-----|------|
| `POST /api/test-schedules` | 스케줄 등록. 본문 `{"suiteId": 1, "environmentId": 1, "cron": "10 9 * * 1-5"}` |
| `GET /api/test-schedules/{id}` | 조회. `nextRunAt`(다음 실행 시각), `lastTriggeredAt`(마지막 실행 시각) 포함 |
| `POST /api/test-schedules/{id}/disable` | 비활성화. `nextRunAt` 제거 |
| `POST /api/test-schedules/{id}/enable` | 활성화. 현재 시각 기준 `nextRunAt` 재계산 |

- cron은 5자리 표준 형식(`분 시 일 월 요일`), `Asia/Seoul` 기준. 예: `10 9 * * 1-5` = 평일 09:10
- 60초 간격 폴링으로 실행 시각 도달 스케줄의 스위트 실행 (`trigger: SCHEDULED`)
- 노트북 절전 등으로 놓친 실행은 1회만 실행 후 다음 시각으로 이동
- 기동 시 `RUNNING`으로 남은 실행은 `ABORTED` 처리
- DB 접속 실패 시 해당 주기 건너뜀. 연결 실패 시 1회, 복구 시 1회 알림

| 설정 | 기본값 | 설명 |
|------|--------|------|
| `sentinel.schedule.enabled` | `true` | 폴링 동작 여부 |
| `sentinel.schedule.zone` | `Asia/Seoul` | cron 해석 시간대 |
| `sentinel.schedule.poll-interval` | `60s` | 폴링 주기 |

## 실패 알림

스케줄 실행에 판정 실패(`FAILED`)·오류(`ERROR`)가 있을 때만 발송. 성공·수동 실행은 미발송

```
[Sentinel] dev 결제 - 12건 중 2건 실패
POST   /billing/v1/charge → status in [2xx]: was 500
GET    /billing/v1/history → response time <= 2000ms: was 3200ms
```

| 알림 | 발송 시점 |
|------|-----------|
| `[Sentinel] {환경} {스위트} - N건 중 M건 실패` | 스케줄 실행 결과에 실패·오류 존재 |
| `[Sentinel] 스케줄 {id} 실행 오류` | 스케줄 실행 자체가 예외로 중단 |
| `[Sentinel] DB 연결 실패 - 스케줄 실행 중단` | DB 접속 실패 첫 주기 1회 |
| `[Sentinel] DB 연결 복구 - 스케줄 실행 재개` | 접속 복구 시 1회, 중단 기간 포함 |

- 채널 미정으로 현재는 WARN 로그(`LoggingNotifier`)로만 기록
- 채널 확정 시 `com.sentinel.application.notification.Notifier` 구현체 추가 후 `NotificationConfig`의 빈 교체

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
