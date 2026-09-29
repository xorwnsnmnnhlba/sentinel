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
| QueryDSL | 7.7 (OpenFeign 포크, 조건 조회·일괄 변경. Boot 미관리라 버전 명시) |
| SnakeYAML Engine | 3.0.1 (케이스 가져오기의 문서 해석, Boot 미관리라 버전 명시) |
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
- 큰 테이블(`test_run`·`test_result`)을 바꾸는 마이그레이션은 1년치 이력으로 적용 시간·조회 성능 확인 (`scripts/perf/README.md`)

## 조회 작성

- 파생 쿼리 메서드는 조건 1~2개의 단순 조회만 (`findByTokenHash`, `countBySuiteIdAndArchivedAtIsNull` 등)
- 그 외 조건 조회·집계·일괄 변경은 QueryDSL. 저장소마다 `XxxQueries`(인터페이스) + `XxxQueriesImpl`(구현)을 두고 저장소가 인터페이스를 상속 (Spring Data 사용자 정의 조각)
  - 엔티티에서 생성한 Q-타입(`QTestRun` 등, `build/generated/sources/annotationProcessor`)으로 필드명·타입을 컴파일 시점에 확인. 엔티티 필드 변경 시 쿼리가 컴파일 오류로 드러남
  - 목록 조건(`RunFilter`, `ChangeLogService.Filter`)은 null이 아닌 값만 `BooleanBuilder`로 조합한 `Predicate`
  - 페이지 목록은 `PageableExecutionUtils.getPage`로 결과가 한 페이지에 모두 들어가면 건수 쿼리 생략
  - 구현은 공유 `EntityManager`로 `JPAQueryFactory`를 직접 생성. 설정 빈이 없어 `@DataJpaTest` 슬라이스에서도 별도 설정 없이 동작
- 네이티브 SQL은 JPQL로 표현할 수 없는 경우만 (`TestResultRepository`의 `PERCENTILE_CONT` 3건, `LATERAL` 1건). SQL 표준 문법, 테이블은 `{h-schema}` 한정
- 성능 조건이 있는 쿼리는 주석에 이유와 측정값 기록 (예: 직전 실행 조회의 시작 시각 상한). 조건을 바꾸면 `scripts/perf`로 실행 계획 확인

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

### 응답 값 추출 (`extractors`)

앞 케이스의 응답 값을 같은 스위트 실행에서 뒤에 실행하는 케이스의 `{{변수}}`로 사용. 예: 로그인 응답의 토큰으로 조회, 등록 응답의 ID로 조회·삭제

```json
"extractors": [
  {"name": "token", "from": "BODY", "expression": "$.data.token", "secret": true},
  {"name": "orderUrl", "from": "HEADER", "expression": "Location"}
]
```

| 속성 | 의미 |
|------|------|
| `name` | 변수 이름. 영문·숫자·`_ . -`, 100자 이하. 케이스 안에서 중복 불가 |
| `from` | `BODY`(응답 본문 JSON 경로) / `HEADER`(응답 헤더, 이름 대소문자 무관, 여러 개면 첫 값) |
| `expression` | `BODY`는 JsonPath, `HEADER`는 헤더 이름 |
| `secret` | 토큰 등 가릴 값. 생략 시 `false` |

- 케이스당 최대 20개. 추출은 판정 규칙 평가 뒤에 하며 결과를 `assertionResults` 끝에 `extract {{token}} from $.data.token` 형태로 덧붙임
- `BODY`는 문자열·숫자·불리언만 추출 (숫자·불리언은 JSON 표기 그대로 문자열로). 결과가 원소 1개인 배열(필터 경로 `$.items[?(@.sku == 'a')].id`)이면 그 원소
- 추출 실패(경로·헤더 없음, `null`, 객체·배열, JSON 아닌 본문)는 해당 규칙 실패로 기록하여 케이스 `FAILED`
- 추출한 값은 환경 변수의 같은 이름보다 우선. 추출에 실패하거나 응답을 받지 못하면 그 변수를 비워, 뒤 케이스는 환경 변수 값으로 조용히 실행되지 않고 `UNRESOLVED_VARIABLE`
- 실행 범위: 같은 스위트 실행 안에서만. 스위트의 실행 순서상 뒤 케이스만 사용 가능
  - 케이스 단건 실행은 앞 케이스를 실행하지 않으므로 추출 변수가 비어 `UNRESOLVED_VARIABLE`. 메시지에 스위트의 다른 케이스가 추출하는 변수임을 안내. 앞 케이스와 함께 확인하려면 여기까지 실행(`throughCaseId`)
- `secret: true` 값은 이후 저장하는 요청(URL·헤더·본문)·응답(헤더·본문)·판정 메시지에서 `****`로 가림 (추출한 케이스의 응답 포함). 결과 메시지는 `extracted (hidden)`. 3자 이하 값은 시크릿과 같이 가리지 않음

### 2. 실행 (API)

```bash
curl -i -X POST localhost:8080/api/runs \
  -H 'Content-Type: application/json' -d '{"caseId": 1, "environmentId": 1}'
```

- 본문 `suiteId`(스위트 전체), `caseId`(케이스 1건), `throughCaseId`(여기까지 실행), `rerunOf`(실패 다시 실행) 중 정확히 하나 지정. 없거나 둘 이상이면 400
- `environmentId`는 `rerunOf` 외에는 필수. `rerunOf`는 원래 실행의 환경을 쓰므로 함께 지정하면 400
- 202 + `Location: /api/runs/{id}`. 실행을 만들고 바로 응답하며 케이스는 백그라운드에서 순서대로 실행. 본문은 그 시점의 실행 (보통 `status: RUNNING`, `results` 비어 있음)
  - 완료 확인: `GET /api/runs/{id}`의 `status`가 `RUNNING`이 아닐 때까지 조회. 진행 중에는 끝난 케이스 결과가 `results`에 차례로 추가되고 `plannedCount`(실행할 케이스 수)로 진행률 계산
  - 동시에 진행 중인 수동 실행이 `sentinel.runner.manual.max-concurrent`(기본 4)이면 실행을 만들지 않고 429. 하나가 끝나면 다시 시도
  - 없는 스위트·케이스·환경(404), 프로젝트 불일치·시크릿 복호화 불가(400)는 실행을 만들기 전에 응답
  - 진행 중 앱이 멈추면 실행은 `RUNNING`으로 남고 재기동 시 `ABORTED`
- 중지: `PATCH /api/runs/{id}` + `{"status": "CANCELED"}`. 진행 중이던 케이스는 끝까지 기록하고 남은 케이스는 보내지 않음 (이미 보낸 요청은 되돌릴 수 없어 케이스 사이에서 멈춤)
  - 수동·스케줄 실행 모두 가능. 응답은 중지된 실행 (`status: CANCELED`, 중지한 사용자 `canceledBy` `{id, name}`, 중지 시각 `finishedAt`)
  - 이미 끝난 실행(완료·중단·중지)은 409, `CANCELED` 외 상태는 400
  - 중지된 실행은 일부 케이스만 실행되므로 스위트 요약·최근 실행·직전 실행 비교 대상에서 제외하고 실패 알림도 보내지 않음
  - 스케줄 실행은 스케줄러 스레드에서 끝까지 진행 (한도와 무관)
- 스위트 실행은 케이스 실행 순서대로 순차 실행, 실패 케이스가 있어도 나머지 계속 실행
  - 실행 순서: 케이스 목록(`GET /api/cases?suiteId=`) 순서와 같음. 새 케이스는 마지막. 스위트 `PATCH`의 `caseOrder`로 변경 ("REST API" 참고)
- 케이스 단건 실행은 케이스가 속한 스위트의 실행으로 기록하고 대상 케이스를 `testCase`(`{id, name}`)로 표시. 스위트 전체 실행은 `null`
  - 케이스 수정 확인용이므로 스위트 단위 집계(스위트 요약의 실행 수·통과율·최근 실행)에서 제외. 실행 이력·응답시간 추세에는 포함
- 여기까지 실행(`throughCaseId`)은 스위트의 처음부터 그 케이스까지 실행 순서대로 실행하고 뒤 케이스는 보내지 않음. 마지막 케이스를 `throughCase`(`{id, name}`)로 표시, 그 외 실행은 `null`
  - 앞 케이스가 추출하는 값을 쓰는 케이스를 스위트 전체 실행 없이 확인하는 용도 (뒤의 등록·삭제 요청 방지)
  - 일부 케이스만 실행하므로 단건 실행과 같이 스위트 단위 집계에서 제외
- 실패 다시 실행(`{"rerunOf": 실행 ID}`)은 그 실행에서 판정 실패·오류가 난 케이스만 같은 환경에서 실행 순서대로 다시 실행. 원래 실행 ID를 `rerunOf`로 표시, 그 외 실행은 `null`
  - 다시 실행하는 케이스가 `{{변수}}`로 쓰는 추출 값을 만드는 앞 케이스는 함께 실행 (연쇄). 같은 변수를 여러 케이스가 추출하면 바로 앞 케이스만. 함께 실행한 케이스는 결과의 `supporting: true` (그 외 결과는 `false`)
  - 케이스는 현재 정의로 실행. 원래 실행 뒤 삭제된 케이스는 제외
  - 원래 실행이 진행 중이거나 다시 실행할 실패 케이스가 없으면(모두 통과, 실패 케이스가 모두 삭제됨) 409. 원래 실행 뒤 스위트·환경이 삭제됐으면 404
  - 다시 실행도 다시 실행 가능 (남은 실패만 반복 확인)
  - 일부 케이스만 실행하므로 단건·여기까지 실행과 같이 스위트 단위 집계·직전 실행 비교에서 제외

### 결과 해석

| 필드 | 의미 |
|------|------|
| `verdict: PASSED` | 판정 규칙 전부 통과 |
| `verdict: FAILED` | 응답 수신 후 판정 규칙 실패. 규칙별 결과는 `assertionResults` (`description`, `passed`, `message`) |
| `verdict: ERROR` | 응답 미수신 또는 전송 전 실패. 사유는 `errorReason` |
| `outcome` | 응답 수신 여부. `RESPONDED`(4xx·5xx 포함) / `ERROR` |
| `errorReason` | `UNRESOLVED_VARIABLE`(변수 누락, 미전송) / `INVALID_REQUEST`(잘못된 URL·헤더) / `TIMEOUT` / `CONNECTION_FAILED` / `IO_ERROR` / `BLOCKED_DESTINATION`(링크 로컬·클라우드 메타데이터 주소, 미전송) / `UNEXPECTED` |

- 저장되는 요청·응답 헤더 중 `Authorization`, `Proxy-Authorization`, `Cookie`, `Set-Cookie`, `X-Api-Key` 값은 `****`로 마스킹. 실제 전송 값은 원본 사용
- 환경 시크릿과 가림 지정 추출 값은 요청·응답과 판정 메시지(`was "..."`)에서 `****`로 가림
- 응답 본문은 64KB(UTF-8 기준) 초과 시 잘라서 저장, `bodyTruncated: true` 표시
- `responseSchema`: 응답 JSON의 구조 지문 `{fields: {경로: [타입]}, complete}`. 스키마 드리프트 감지의 기준 데이터
  - 예: `{"$": ["object"], "$.items": ["array"], "$.items[]": ["object"], "$.items[].id": ["number"], "$.items[].tag": ["null", "string"]}`
  - 값은 남기지 않음. 타입은 object·array·string·number·boolean·null, 한 경로에 여러 타입이 관측되면 모두 기록
  - 배열은 모든 요소를 `[]` 하나로 합침. 동적 키(형제 키 50개 초과, 또는 2개 이상이면서 모두 숫자·UUID·날짜 형태)는 `*`로 축약. 식별자가 아닌 키는 `$['a.b']`
  - 잘라서 저장하는 본문과 달리 전체 본문(최대 5MB)으로 계산. 깊이 20·경로 1,000개 한도를 넘으면 `complete: false`
  - Content-Type이 JSON(`application/json`, `+json`)이거나 없을 때 본문이 `{`·`[`로 시작하면 대상. 그 외·해석 실패·응답 없음은 `null`
  - 원문 보관기간(30일)이 지나도 삭제하지 않음
- `drift`: 같은 케이스·환경의 이전 실행 대비 구조 변화 `{status, observations, changes}`. 지문이 없으면 `null`
  - 기준선: 이 실행보다 앞선 실행의 지문 최근 20건(`sentinel.schema-drift.window`). 3건(`min-observations`) 미만이면 `status: COLLECTING`, 비교하지 않음
  - 필수 필드: 상위 경로가 있었던 모든 응답에 존재 (빈 배열·선택 필드인 상위 객체가 없던 응답은 관측에서 제외). null은 한 번이라도 관측되면 허용
  - `changes[]`: `{level, kind, path, expected, actual}`. 등급(심한 순) → 경로 순

    | level | kind | 의미 |
    |-------|------|------|
    | `BREAKING` | `REMOVED` | 필수 필드 없음 |
    | `BREAKING` | `TYPE_CHANGED` | null 외 타입이 새로 나타남 (예: number → string, object → array) |
    | `WARNING` | `NULLABLE` | null이 새로 나타남 |
    | `INFO` | `ADDED` | 새 필드 (하위 필드는 상위 한 건으로) |
  - 보고하지 않는 경우: 빈 배열·빈 동적 키, 상위가 없거나 null인 필드의 하위, 타입이 바뀐 경로의 하위, 일부만 기록한(`complete: false`) 지문의 없어진 필드
  - 기준선은 조회 시점 계산이며 저장하지 않음. 실행 1건의 모든 케이스를 쿼리 1회로 조회하며 케이스마다 최근 창 크기만큼만 읽음 (이력 길이와 무관). 기준선 저장·승인·알림은 이후 단계
- 요청·응답 헤더와 본문은 보관기간(기본 30일) 경과 후 삭제 ("결과 원문 보관기간" 참고)
- `previousRun`·`change`: 직전 실행 대비 변화 (아래 "직전 실행 대비 변화")

### 직전 실행 대비 변화

스위트 전체 실행의 상세는 같은 스위트·환경의 완료된 직전 스위트 전체 실행(수동·스케줄 무관)과 케이스별 판정을 비교

- `previousRun`: 비교 기준 `{id, startedAt}`. 직전 실행이 없으면 `null`
- `results[].change` (실패는 판정 실패·오류)

  | change | 의미 |
  |--------|------|
  | `NEWLY_FAILING` | 통과 → 실패 |
  | `RECOVERED` | 실패 → 통과 |
  | `STILL_FAILING` | 실패 → 실패 |
  | `UNCHANGED` | 통과 → 통과 |
  | `NEW` | 직전 실행에 없던 케이스 |

- 비교하지 않는 실행(`previousRun`·`change` 모두 `null`): 첫 실행, 케이스 단건 실행·여기까지 실행·실패 다시 실행(일부 케이스만 실행)
- 직전 실행 후보에서 제외: 케이스 단건 실행·여기까지 실행·실패 다시 실행, 완료되지 않은 실행(`RUNNING`·`ABORTED`·`CANCELED`)
- 오래된 실행을 조회해도 그 실행 바로 앞 실행과 비교 (조회 시점에 계산, 저장하지 않음)
- 요청 URL의 변수 값은 인코딩하지 않음. 공백 등 URL에 쓸 수 없는 문자는 `INVALID_REQUEST`
- 링크 로컬(`169.254.0.0/16`, `fe80::/10`)과 클라우드 메타데이터 주소(`fd00:ec2::254`, `100.100.100.200`)로는 전송하지 않고 `BLOCKED_DESTINATION`
  - 누구나 기본 URL을 정할 수 있고 응답 본문이 이력에 남으므로, 클라우드 인스턴스 자격증명을 읽는 통로 차단. 호스트 이름은 해석한 주소로 판단
  - 사설망(10.x, 192.168.x 등)과 루프백은 내부 API 점검이 목적이라 허용
  - 연결 직전에 한 번 해석한 주소를 확인하고 그 주소로만 연결. 확인과 연결 사이에 DNS 응답을 바꾸는 공격(DNS rebinding) 차단
  - HTTPS 인증서 확인·SNI는 원래 호스트 이름 기준 (IP로 바꿔 연결하지 않음)

### 전송 방식

- HTTP 클라이언트는 Apache HttpClient 5.6 (`RestClient` + `HttpComponentsClientHttpRequestFactory`). 연결할 주소를 직접 해석·확인하는 해석기(`DnsResolver`)를 지정할 수 있어서 사용 (JDK HttpClient는 미지원)
- 서버 응답을 그대로 기록하도록 클라이언트의 자동 처리 미사용
  - 리다이렉트 미추적 (3xx 그대로 기록)
  - 재시도 없음. 기본 설정은 429·503(`Retry-After`)과 일부 전송 오류를 다시 보내 첫 응답이 가려짐
  - 압축 요청(`Accept-Encoding`) 없음, 쿠키 저장·자동 전송 없음 (케이스에 적은 헤더만 전송)
- 연결 재사용. 1초 이상 쉰 연결은 사용 전 확인, 30초 이상 쉰 연결은 정리. 동시 연결 한도 200 (Tomcat 기본 요청 스레드 수)
- 제한 시간: 연결 `sentinel.runner.http.connect-timeout`(기본 5초) 초과 시 `CONNECTION_FAILED`, 응답 대기 `read-timeout`(기본 30초) 초과 시 `TIMEOUT`
- 수동 실행은 실행마다 가상 스레드 하나에서 진행 (대부분 GW 응답 대기이므로 스레드를 점유하지 않음). 동시 수동 실행 수는 `sentinel.runner.manual.max-concurrent`(기본 4, 1 미만이면 기동 실패)

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
- 로그인 실패 제한: 같은 이메일로 15분 안에 5회 실패하면 15분 동안 올바른 비밀번호로도 429 + `Retry-After`(초)
  - 없는 계정도 같은 방식으로 세어 잠김 여부로 계정 존재를 알 수 없음. 성공하면 실패 횟수 초기화
  - IP 기준은 미사용 (Spring에는 Next.js 서버 주소만 보임). 다른 사람이 특정 계정을 최대 15분 잠글 수 있음
  - 메모리 보관이라 재시작하면 초기화 (단일 인스턴스 전제)
- 로그인 없이 허용하는 링크 API(`/api/invites/`, `/api/password-resets/`)는 경로가 깨끗할 때만 예외. 점 세그먼트, `;`, 인코딩된 `.`·`/`·`\`, `//`가 있으면 인증 요구 (라우팅의 경로 정리 방식에 기대지 않음)
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
| 케이스 | `GET /api/cases?suiteId=&q=` | `GET /api/cases/{id}` | `POST /api/cases`, `PATCH /api/cases/{id}` | `DELETE /api/cases/{id}` |
| 실행 | `GET /api/runs?projectId=&suiteId=&environmentId=&status=&from=&to=&failing=&page=0&size=20` | `GET /api/runs/{id}` | `POST /api/runs`, `PATCH /api/runs/{id}`(중지) | |
| 스케줄 | `GET /api/schedules?suiteId=` | `GET /api/schedules/{id}` | `POST /api/schedules`, `PATCH /api/schedules/{id}` | `DELETE /api/schedules/{id}` |
| 점검 시간 | `GET /api/maintenance-windows?environmentId=` | | `POST /api/maintenance-windows`, `PATCH /api/maintenance-windows/{id}` | `DELETE /api/maintenance-windows/{id}` |
| 응답시간 추세 | `GET /api/response-time-trends?caseId=&environmentId=&from=&to=` | | | |
| 응답시간 요약 | `GET /api/response-time-summaries?suiteId=&environmentId=&from=&to=` | | | |
| 응답시간 변화 | `GET /api/response-time-changes?from=&to=&limit=5` | | | |
| 스위트 요약 | `GET /api/suite-summaries?from=&to=` | | | |
| 변경 이력 | `GET /api/change-logs?targetType=&targetId=&projectId=&suiteId=&page=0&size=20` | | | |

- 케이스 검색 `q`: 이름·경로 부분 일치 (대소문자 무시, 앞뒤 공백 제거, `%`·`_`는 문자 그대로). 스위트별 실행 순서, 최대 100건. `suiteId`와 함께 쓰면 그 스위트 안에서만. 빈 검색어·200자 초과는 400
- 필터 파라미터 생략 시 전체. 존재하지 않는 부모 ID로 필터링하면 빈 목록(200), 단건 조회만 미존재 시 404 ProblemDetail
- 응답 항목에 부모 ID 포함 (`environment.projectId`, `suite.projectId`, `case.suiteId`)
- 스위트는 `caseCount`, 케이스는 요청 정의(`request`)와 판정 규칙(`assertions`)·응답 값 추출(`extractors`), 환경은 `baseUrl`과 변수 포함
- 실행 목록: 최신순, 페이지 응답 `{content, page, size, totalElements, totalPages}` (`page`는 0부터, `size`는 1~100으로 보정)
  - 행: 프로젝트·스위트·환경의 `{id, name}`, `trigger`, `status`, 시각, `summary`(전체·통과·실패·오류), `triggeredBy`
  - `triggeredBy`: 수동 실행을 시작한 사용자 `{id, name}`(이메일). 스케줄 실행과 이전 기록은 `null`
  - `testCase`: 케이스 단건 실행의 대상 `{id, name}`. 스위트 전체 실행은 `null`. 삭제(보관)된 케이스도 이름 표시
  - `throughCase`: 여기까지 실행의 마지막 케이스 `{id, name}`. 그 외 실행은 `null`
  - `rerunOf`: 실패 다시 실행의 원래 실행 ID. 그 외 실행은 `null`
  - `plannedCount`: 실행할 케이스 수. 진행 중(`RUNNING`)이면 `summary.total`(끝난 케이스 수)과 비교해 진행률 표시. 이전 실행은 `null`
  - 필터: `projectId`, `suiteId`, `environmentId`, `status`(`RUNNING`/`COMPLETED`/`ABORTED`/`CANCELED`), 시작 시각 기간 `from`·`to`, `failing=true`(판정 실패·오류 결과가 하나 이상 있는 실행). 여러 개를 함께 지정하면 모두 만족하는 실행만
  - `from`·`to`는 `yyyy-MM-dd`이며 `sentinel.schedule.zone`(기본 `Asia/Seoul`) 기준. 시작일 00:00부터 종료일 다음 날 00:00 직전까지 (종료일 당일 포함)
  - 실행 건수와 무관하게 페이지당 쿼리 최대 4회 (이름 함께 로딩, 전체 건수, 실행 주체 일괄 조회, 단건 실행 대상 일괄 조회). 판정 건수와 `failing` 조건은 실행 행에 보관한 판정 수(V10)를 사용하여 결과 테이블을 읽지 않음
- 실행 상세: 스위트·환경·케이스 이름과 케이스별 결과, 수동 실행이면 실행 주체 포함
  - 실행 주체는 ID로 보관하고 이름은 조회 시 확인. 사용자를 삭제해도 이력에는 그대로 표시
- 등록·수정
  - 등록은 201 + `Location`, 본문은 단건 조회와 같은 형태. 수정은 200 + 수정 후 항목
  - `PATCH`는 부분 수정. 생략(null)한 필드는 유지, `description`은 빈 문자열로 제거
  - 환경 `variables`, 케이스 `request`·`assertions`·`extractors`는 지정 시 전체 교체. `assertions: []`는 기본 판정(2xx)으로 복귀
  - 스위트 `caseOrder`: 케이스 실행 순서. 스위트의 사용 중인 케이스 ID 전체를 실행할 순서대로 (예: `{"caseOrder": [12, 10, 11]}`). 새 케이스는 마지막 순서로 등록 (케이스 등록의 `afterCaseId`를 지정하면 그 케이스 바로 뒤)
    - 목록이 스위트의 현재 케이스와 다르면(그사이 추가·삭제) 409, 같은 ID 중복·`null`은 400. 거부 시 함께 보낸 이름·설명 변경도 반영하지 않음
  - 같은 부모 아래 사용 중인 이름과 중복 시 409 (프로젝트는 전체에서 유일). 앞뒤 공백은 제거 후 비교
  - 잘못된 값(빈 이름, 길이 초과, 잘못된 `baseUrl`·경로·메서드·판정 규칙·추출 규칙)은 400, 미존재·보관된 부모는 404
  - 부모 변경(다른 프로젝트·스위트로 이동)은 미지원

| 등록 | 본문 예시 |
|------|-----------|
| 프로젝트 | `{"name": "billing", "description": "결제"}` |
| 환경 | `{"projectId": 1, "name": "dev", "baseUrl": "https://gw.dev.internal", "variables": {"userId": "42"}, "secrets": {"token": "..."}}` |
| 스위트 | `{"projectId": 1, "name": "smoke", "description": null}` |
| 케이스 | `{"suiteId": 1, "name": "결제 내역 조회", "request": {"method": "GET", "path": "/billing/v1/history?userId={{userId}}", "headers": {"Accept": ["application/json"]}, "body": null}, "assertions": [{"type": "statusCode", "anyOf": ["200"]}]}` |

- 케이스 등록의 `afterCaseId`(선택): 같은 스위트의 그 케이스 바로 뒤 실행 순서로 등록하고 뒤 케이스 순서를 한 칸씩 밈. 다른 스위트의 케이스면 400, 없거나 삭제된 케이스면 404. 생략 시 마지막 (화면의 케이스 복제가 원본 바로 뒤에 두는 데 사용)

### 응답시간 추세

저장된 실행 결과에서 p50·p95·최대 응답시간을 바로 집계 (별도 집계 테이블 없음)

| API | 설명 |
|-----|------|
| `GET /api/response-time-trends?caseId=&environmentId=` | 케이스 1건의 일별 값. 기간 생략 시 최근 30일 |
| `GET /api/response-time-summaries?suiteId=&environmentId=` | 스위트의 케이스별 값 (기간 전체). 기간 생략 시 최근 7일 |
| `GET /api/response-time-changes?limit=5` | 케이스·환경별 p95를 바로 앞의 같은 길이 기간과 비교, 가장 나빠진 순. 기간 생략 시 최근 7일, `limit` 1~50 |

```json
{"caseId": 3, "environmentId": 1, "from": "2026-08-26", "to": "2026-09-24",
 "points": [{"date": "2026-09-24", "samples": 144, "p50": 120, "p95": 340, "max": 910}]}
```

- 대상은 응답을 받은 결과(4xx·5xx·판정 실패 포함). 타임아웃·연결 실패 등 응답을 받지 못한 결과는 제외
- 환경은 필수. 환경마다 응답시간이 달라 섞어서 집계하지 않음. 다른 프로젝트의 환경은 400
- 기간 `from`·`to`는 `yyyy-MM-dd`, `sentinel.schedule.zone`(기본 `Asia/Seoul`) 기준 실행 시작 날짜, 종료일 포함, 최대 90일
- 추세는 기간의 모든 날짜를 포함하고 결과가 없는 날은 `samples: 0`, 값 `null`. 요약은 스위트의 모든 케이스(보관 제외, 실행 순서)를 포함
- 백분위는 SQL 표준 `PERCENTILE_CONT`(연속 보간) 결과를 ms 단위로 반올림
- 원문 보관기간 정리(`raw_purged_at`)는 소요 시간을 지우지 않으므로 오래된 실행도 집계 대상
- 변화 비교: `change`는 이전 대비 p95 증가율(0.42는 42% 느려짐, 음수는 빨라짐). 두 기간 모두 결과 3건 이상인 케이스·환경만, 보관된 케이스·환경 제외
  - 응답: `from`·`to`, 비교 기간 `previousFrom`·`previousTo`, `changes[]`(`testCase`·`suite`·`environment`, `previousP95`·`currentP95`, `change`, 기간별 건수)

### 스위트 요약

`GET /api/suite-summaries?from=&to=`: 대시보드용 스위트별 요약. 기간 생략 시 최근 7일, 최대 90일

- 보관되지 않은 모든 스위트 (프로젝트·스위트 이름 순), 실행한 적 없는 스위트도 포함
- 항목: `suite`·`project`, 기간 안 실행 수 `runs`, 그중 실패·오류가 있는 실행 수 `failingRuns`, 판정별 결과 수 `results`, 통과 결과 비율 `passRate`(0~1, 결과 없으면 `null`), 기간과 무관한 최근 실행 `lastRun`(없으면 `null`)
- 모든 값은 스위트 전체 실행 기준. 케이스 단건 실행·여기까지 실행·실패 다시 실행·중지된 실행은 제외 (일부 케이스만 통과해도 스위트가 통과한 것처럼 보이지 않도록)
- 스위트 수와 무관하게 쿼리 3회. 실행 행의 판정 수(V10)를 합산하여 결과 테이블을 읽지 않음

### 케이스 가져오기 (OpenAPI 3, Postman)

문서를 케이스 후보로 변환해 확인한 뒤, 고른 후보만 가져오는 2단계 방식

| API | 설명 |
|-----|------|
| `POST /api/case-import-previews` | 변환만 하고 저장하지 않음. 본문 `{"suiteId": 1, "format": "OPENAPI_3", "content": "<문서 원문>"}`. `format`은 `OPENAPI_3`(JSON·YAML) 또는 `POSTMAN`(Collection v2.0·v2.1 JSON) |
| `POST /api/case-imports` | 한 트랜잭션으로 등록. 본문 `{"suiteId": 1, "cases": [{"name": "...", "request": {...}, "assertions": [...]}]}`. 201 + `{"suiteId", "cases": [등록한 케이스]}` |

- 미리보기 응답: `title`, `version`, `servers`(환경 기본 URL 설정 참고용), `cases`, `warnings`(문서 단위)
  - 후보: `source`(원본 위치. 예: `GET /pets/{petId}`, `GET {{baseUrl}}/orders`), `name`, `request`, `assertions`, `warnings`(생략·대체한 내용), `conflict`(스위트에 같은 이름 있음)
  - 후보를 그대로(또는 이름·요청을 고쳐) `cases`로 보내면 가져오기. `source`·`warnings`·`conflict`는 무시
- 가져오기는 한 건이라도 실패하면 전부 미등록. 이름 중복(스위트 기존 케이스, 목록 안 중복)은 409, 잘못된 케이스는 400 (`cases[1] 'Broken': ...`처럼 위치 표시)
- 문서는 5MB(문자 수)까지, 한 번에 가져오기는 500건까지
- Swagger 2.0 등 OpenAPI 3이 아닌 문서, Postman v1 컬렉션·환경 파일, 해석할 수 없는 문서, 작업·요청이 없는 문서는 400

| OpenAPI | 케이스 |
|---------|--------|
| `paths` × 메서드 | 케이스 1건. 문서 순서 유지. `trace`는 미지원 메서드라 제외 (문서 경고) |
| 이름 | `summary` → `operationId` → `GET /pets/{petId}` 순. 문서 안 중복은 ` (2)`, ` (3)` 추가 |
| 경로 파라미터 `{petId}` | 변수 `{{petId}}`. 변수 이름에 쓸 수 없는 문자는 `_`로 바꾸고 경고 |
| 필수 query·header 파라미터 | `?status={{status}}`, `X-Request-Id: {{X-Request-Id}}`. 선택 파라미터는 생략, cookie는 미지원 경고. `Accept`·`Content-Type`·`Authorization` header 파라미터는 OpenAPI 규칙대로 무시 |
| 경로 공통 `parameters` | 작업에 병합. 같은 이름·위치는 작업 쪽 우선 |
| JSON 요청 본문 (`application/json`, `*+json`) | 미디어 타입 `example` → `examples`의 첫 값 → 스키마 `example` 순. 있으면 본문과 `Content-Type` 헤더 |
| 예시 없는 JSON 요청 본문 | 스키마로 뼈대 생성 후 "자리 표시 값을 바꿔 주세요" 경고 (아래 규칙). 스키마도 없으면 빈 본문으로 경고 |
| 그 밖의 요청 본문 (`multipart/form-data` 등) | 빈 본문으로 경고 |
| 응답 코드 | 선언된 2xx(`200`, `201`, `2XX` → `2xx`)로 상태 코드 판정. 없으면 `2xx`로 경고 |
| `$ref` | 문서 내부 참조(`#/...`)만 해석. 외부 파일 참조·순환 참조는 경고 후 생략 |
| YAML 앵커·별칭 | 펼쳐서 해석. 별칭 폭증(billion laughs) 방지를 위해 별칭 50개까지 |
| 보안 스킴 (`security`) | 작업의 `security`, 없으면 문서 공통. 대안이 여럿이면 첫 번째, `security: []`는 인증 없음. 값은 스킴 이름의 변수 (아래 표) |
| `servers` | 케이스에 미반영. 기본 URL은 환경에서 지정 |

| 보안 스킴 | 요청 |
|-----------|------|
| `http` `bearer`, `oauth2`, `openIdConnect` | `Authorization: Bearer {{스킴 이름}}`. 토큰 발급은 미지원이라 발급받은 토큰을 시크릿으로 등록 |
| `http` `basic` | `Authorization: Basic {{스킴 이름}}`. 값은 `아이디:비밀번호`를 Base64로 인코딩한 문자열 |
| `apiKey` header·query | `X-API-Key: {{스킴 이름}}`, `?tenant={{스킴 이름}}`처럼 지정한 이름·위치에 추가 |
| `apiKey` cookie, 그 밖의 `http` 스킴(digest 등) | 미지원 경고 |

- 사용한 스킴마다 "어떤 변수를 시크릿으로 등록해야 하는지" 문서 단위 안내 1건 (`warnings`)

스키마 기반 본문 뼈대 (값은 자리 표시용)
- 스키마의 `example` → `default` → `const` → `enum` 첫 값이 있으면 그대로 사용
- 객체는 `readOnly`를 제외한 모든 속성, 배열은 항목 1개. `type` 생략 시 `properties`·`items`로 추정
- `allOf`는 객체 병합, `oneOf`·`anyOf`는 첫 스키마. OpenAPI 3.1의 `type: [string, "null"]`은 null이 아닌 종류
- 문자열은 `format`별 값(`date-time` → `2026-01-01T00:00:00Z`, `email` → `user@example.com`, `uuid` 등), 그 외 `"string"`. 숫자 0, 불리언 false
- 중첩은 6단계까지, 자기 참조 스키마(트리 등)는 되풀이하지 않음

| Postman | 케이스 |
|---------|--------|
| 요청 | 케이스 1건. 컬렉션 순서 유지. 폴더는 이름 앞에 `폴더 / 하위 폴더 / 요청`. 지원하지 않는 메서드(`COPY` 등)는 제외 (문서 경고) |
| URL | 호스트(`{{baseUrl}}`, `https://...`)는 빼고 경로·query만. 호스트는 `servers`로 안내 (호스트가 컬렉션 변수 하나이고 값이 주소면 값도 표시). 꺼 둔 query는 생략 |
| 경로 변수 `:orderId` | 컬렉션에 적힌 값, 없으면 변수 `{{orderId}}` |
| 헤더 | 꺼 둔 헤더는 생략. `{{변수}}` 문법이 같아 그대로 유지 |
| 본문 `raw` | 그대로. 언어가 json·xml·text면 `Content-Type` 추가 (직접 지정한 헤더 우선) |
| 본문 `urlencoded` | `key=value&...`로 인코딩(`{{변수}}`는 그대로), `Content-Type: application/x-www-form-urlencoded` |
| 본문 `graphql` | `{"query": ..., "variables": ...}` JSON |
| 본문 `formdata`·`file` | 빈 본문으로 경고 |
| 인증 | 요청 → 상위 폴더 → 컬렉션 순으로 상속, `noauth`는 상속 끊음. `bearer`·`oauth2` → `Authorization: Bearer`, `apikey` → 지정한 header·query, `basic` → 항상 `Authorization: Basic {{basicAuth}}` (Base64 인코딩 값을 시크릿으로), 그 밖(digest 등)은 경고 |
| 평문 자격증명 | 인증 값, `Authorization`·`Proxy-Authorization`·`Cookie`·`X-Api-Key` 헤더 값이 `{{변수}}`가 아니면 변수(`{{bearerToken}}`, `{{apiKey}}`, `{{authorization}}` 등)로 바꾸고 경고. 값은 케이스·응답 어디에도 남기지 않음 |
| 스크립트 (pre-request·test) | 미지원 경고. 내용 없는 스크립트는 무시 |
| 판정 | 저장된 예시 응답의 2xx 코드, 없으면 `2xx` |
| 컬렉션 변수 | 가져오지 않음. 값은 자격증명일 수 있어 이름만 안내 (`warnings`) |

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

## 변경 이력

설정을 누가·언제·무엇을 바꿨는지 기록. 등록·수정·삭제 API가 변경과 같은 트랜잭션에서 남기므로 변경이 거부(400·404·409)되면 기록도 없음

| 대상 (`targetType`) | 기록 시점 | 변경한 사용자 (`actor`) |
|------|------|------|
| `PROJECT`·`ENVIRONMENT`·`SUITE`·`CASE`·`SCHEDULE`·`MAINTENANCE_WINDOW` | 등록·수정·삭제 (케이스 가져오기는 케이스마다 등록) | 요청한 사용자 |
| `INVITE` | 초대 링크 발급 (`targetName`은 이메일) | 관리자 |
| `USER` | 가입(초대 수락), 비밀번호 변경·재설정, 재설정 링크 발급, 삭제 | 가입·비밀번호는 본인, 링크 발급·삭제는 관리자 |

- 항목: `{id, occurredAt, actor: {id, name(이메일)}, targetType, targetId, targetName, projectId, action, fields}`
  - `action`: `CREATED` / `UPDATED` / `DELETED`
  - `targetName`: 변경 당시 이름. 항목을 삭제해도 표시 가능. 스케줄은 스위트 이름, 점검 시간은 환경 이름
  - `projectId`: 소속 프로젝트. 사용자·초대는 `null`
  - `suiteId`: 소속 스위트. 스위트는 자신, 케이스·스케줄은 소속 스위트, 그 외는 `null`
  - `fields`: 수정에서 값이 실제로 바뀐 항목 이름. 등록·삭제는 빈 목록
- `fields` 값: 프로젝트·스위트 `name`·`description`, 스위트 `caseOrder`, 환경 `name`·`baseUrl`·`variables.<키>`·`secrets.<이름>`, 케이스 `name`·`request`·`assertions`·`extractors`, 스케줄 `environment`·`cron`·`enabled`, 점검 시간 `startsAt`·`endsAt`·`reason`, 사용자 `password`·`passwordResetLink`
- 값은 남기지 않음 (변수 값, 시크릿, 비밀번호, 링크 토큰 모두). 시크릿은 암호화 값을 비교할 수 없어 요청에 있던 이름을 모두 변경으로 기록
- 같은 값으로 저장한 수정은 기록하지 않음. 삭제는 대상 1건만 기록 (프로젝트 삭제 시 함께 보관되는 환경·스위트·케이스·스케줄은 따로 기록하지 않음)
- 스케줄러가 다음 실행 시각을 옮기는 것과 실행 자체는 기록하지 않음 (실행은 실행 이력)
- 조회: 최신순, `size` 1~100. `targetType`+`targetId`는 항목 1건, `projectId`는 프로젝트와 그 안의 항목 전체, `suiteId`는 스위트와 그 안의 케이스·스케줄. 잘못된 `targetType`은 400
- 이 기능 이전(V13 전)의 변경은 기록이 없음

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
- 환경이 점검 중이면 그 환경의 스케줄은 실행하지 않고 다음 실행 시각으로 넘어감 (아래 "점검 시간")
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

### 점검 시간

배포·점검으로 실패가 예상되는 동안 환경의 스케줄 실행을 건너뜀. 실행 기록이 없으므로 실패 알림·스위트 요약·직전 실행 비교에 점검 중 실패가 섞이지 않고, 끝나면 따로 조치 없이 다시 실행

| API | 설명 |
|-----|------|
| `POST /api/maintenance-windows` | 등록. 본문 `{"environmentId": 1, "startsAt": "2026-09-29T10:00:00+09:00", "endsAt": "2026-09-29T11:00:00+09:00", "reason": "GW 배포"}`. 201 + `Location` |
| `GET /api/maintenance-windows?environmentId=` | 끝나지 않은 점검(진행 중·예정), 시작 순. `environmentId` 생략 시 전체. 삭제된 환경의 점검은 제외 |
| `PATCH /api/maintenance-windows/{id}` | 부분 변경(연장·단축·사유). 생략한 필드는 유지, `"reason": ""`은 사유 삭제. 이미 끝난 점검은 409 |
| `DELETE /api/maintenance-windows/{id}` | 삭제. 예정된 점검은 취소, 진행 중인 점검은 바로 끝나 다음 스케줄부터 실행. 204 |

- 항목: `{id, environment: {id, name}, projectId, startsAt, endsAt, reason, active, createdBy: {id, name(이메일)}}`. `active`는 지금 점검 중(스케줄 건너뜀), `false`는 예정
- 기간: `startsAt` 이상 `endsAt` 미만. `endsAt`은 `startsAt` 뒤이고 현재 이후, 기간은 최대 7일 (끄고 잊어 모니터링이 멈추는 것 방지). 이미 시작한 시각으로도 등록 가능(지금부터 점검). 어기면 400
- `reason`: 선택, 앞뒤 공백 제거 후 200자 이하
- 건너뛴 스케줄은 `nextRunAt`만 다음 시각으로 옮기고 `lastTriggeredAt`(마지막 실행 시각)은 그대로. INFO 로그 1줄
- 겹치는 점검 허용 (하나라도 진행 중이면 건너뜀). 수동 실행은 점검 중에도 가능 (점검 중 상태 확인용)
- 등록·변경·삭제는 변경 이력(`MAINTENANCE_WINDOW`)에 기록 (누가 스케줄을 멈췄는지)

```
Skipped schedule 4 (suite 3) at 2026-09-29T01:10:00Z: environment 2 is under maintenance until 2026-09-29T02:00:00Z
```

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

구조 변화 판정 (`drift`)

| 설정 | 기본값 | 설명 |
|------|--------|------|
| `sentinel.schema-drift.window` | `20` | 기준선에 쓰는 이전 실행 수 (같은 케이스·환경) |
| `sentinel.schema-drift.min-observations` | `3` | 판정에 필요한 최소 이전 실행 수. 1 이상, window 이하가 아니면 기동 실패 |

## 실패 알림

스케줄 실행 결과를 같은 스위트·환경의 이전 스케줄 실행과 비교하여 발송. 수동 실행은 발송·비교 대상 아님

```
[Sentinel] dev 결제 - 12건 중 2건 실패 (새로 실패 1건)
[새로 실패] 결제 승인 · POST /billing/v1/charge → status in [2xx]: was 500
[3회째 · 09-24 09:10부터] 결제 내역 · GET /billing/v1/history → response time <= 2000ms: was 3200ms
결과: https://sentinel.example.com/runs/12
```

```
[Sentinel] dev 결제 - 복구, 12건 모두 통과
다시 통과: 결제 승인, 결제 내역
실패 지속: 스케줄 실행 3회, 09-24 09:10부터 2시간 0분
결과: https://sentinel.example.com/runs/15
```

| 알림 | 발송 시점 |
|------|-----------|
| `[Sentinel] {환경} {스위트} - N건 중 M건 실패 (새로 실패 K건)` | 스케줄 실행 결과에 판정 실패(`FAILED`)·오류(`ERROR`) 존재. 모두 이전부터 실패하던 케이스면 `(계속 실패 중)` |
| `[Sentinel] {환경} {스위트} - 복구, N건 모두 통과` | 모두 통과했고 직전 스케줄 실행에 실패가 있었을 때 1회 |
| `[Sentinel] 스케줄 {id} 실행 오류` | 스케줄 실행 자체가 예외로 중단 |
| `[Sentinel] DB 연결 실패 - 스케줄 실행 중단` | DB 접속 실패 첫 주기 1회 |
| `[Sentinel] DB 연결 복구 - 스케줄 실행 재개` | 접속 복구 시 1회, 중단 기간 포함 |

- 실패 케이스마다 `[새로 실패]` 또는 `[N회째 · 시작 시각부터]` 표시, 새로 실패한 케이스가 앞. 케이스 이름과 요청을 함께 표시
- 연속 여부는 이전 스케줄 실행 20회까지 확인. 모두 실패였으면 `[21회 이상 · …]`
- 복구 알림: 직전 실행에서 실패했다가 통과한 케이스 이름(최대 10개), 실패가 이어진 스케줄 실행 수와 기간
- 수동 실행을 비교에서 빼는 이유: 수정 확인용으로 실행하는 경우가 많아, 넣으면 다음 스케줄 실행의 복구 알림이 누락될 수 있음
- 계속 통과하면 발송하지 않음. 시각은 `sentinel.schedule.zone` 기준

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
