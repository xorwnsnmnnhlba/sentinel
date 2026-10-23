# sentinel-api

Sentinel 백엔드. 테스트 케이스 실행(러너), 스케줄 실행, 결과 저장, web(BFF) 대상 API 담당

## 스택

| 구분 | 버전 |
|------|------|
| Java | 25 (로컬 Azul Zulu 25.0.4.1) |
| Gradle | 9.8.1 (Wrapper, 배포판 체크섬 고정) |
| Spring Boot | 4.1.1 |
| Spring Framework | 7.0.9 |
| Spring Data JPA | 4.1.1 |
| Hibernate ORM | 7.4.5.Final |
| Jakarta Persistence | 3.2.0 |
| Hibernate Validator | 9.1.3.Final |
| Jackson | 3.1.7 (취약점 수정판으로 직접 지정, `build.gradle`) |
| Tomcat (내장) | 11.0.27 (취약점 수정판으로 직접 지정, `build.gradle`) |
| HikariCP | 7.0.2 |
| PostgreSQL JDBC | 42.7.13 |
| DB | PostgreSQL 18, AWS RDS `sentinel` 스키마 |
| Flyway | 12.4.0 |
| QueryDSL | 7.7 (OpenFeign 포크, 조건 조회·일괄 변경. Boot 미관리라 버전 명시) |
| SnakeYAML Engine | 3.2 (케이스 가져오기의 문서 해석, Boot 미관리라 버전 명시) |
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
./gradlew test jacocoTestReport   # 테스트 + 커버리지 보고서 (build/reports/jacoco/test/html)
./gradlew pitest    # 변이 검사 보고서 (build/reports/pitest)
```

- 컴파일러 경고는 모두 켜고 경고가 있으면 실패 (`-Xlint:all` + `-Werror`, `build.gradle`). 제외: `classfile`(QueryDSL jar), `serial`, `processing`
- 커버리지(JaCoCo)는 보고만 하고 수치 기준으로 실패시키지 않음. 테스트가 닿지 않는 곳을 찾는 용도. QueryDSL Q 클래스 제외. CI는 실행 요약에 줄·분기 비율, 산출물 `api-coverage`에 HTML 보고서
- 변이 검사(PIT)는 외부 연결 없이 도는 판정·값 추출·구조 지문(`runner`)과 문서 변환(`importing`)만 대상. 코드를 조금씩 바꿔 테스트가 잡아내는지 확인 (커버리지는 실행만 되고 확인하지 않는 테스트를 찾지 못함). 주 1회 CI(`.github/workflows/mutation.yml`)가 실행 요약에 점수, 산출물 `api-mutation`에 HTML 보고서. 점수 기준으로 실패시키지 않음

테스트는 Testcontainers 기반이므로 Docker 실행 필요. DB 환경변수·RDS 접속 불필요

Gradle 배포판은 체크섬 고정 (`gradle/wrapper/gradle-wrapper.properties`의 `distributionSha256Sum`). 내려받은 배포판이 다르면 wrapper가 실행을 거부. 버전을 올릴 때는 공식 체크섬(`https://services.gradle.org/distributions/gradle-<버전>-bin.zip.sha256`)과 함께 변경

```bash
./gradlew wrapper --gradle-version <버전> --gradle-distribution-sha256-sum <체크섬>
```

Docker 이미지(`Dockerfile`)는 의존성 단계(`downloadDependencies`: jar까지 내려받음)와 컴파일 단계를 나누고, 컴파일은 네트워크 없이(`--network=none`, `--offline`) 실행. 소스만 바뀐 빌드는 Maven Central 없이 끝나고, 빠진 의존성은 빌드 실패로 바로 드러남

| 컨테이너 | 이미지 | 용도 |
|----------|--------|------|
| PostgreSQL | `postgres:18-alpine` | 스프링 컨텍스트·JPA 테스트 (`TestcontainersConfiguration`). 테스트 실행 전체에서 컨테이너 1개, 컨텍스트마다 새 데이터베이스(격리). 연결 한도 300 |
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
  - 실행 도중 예상하지 못한 오류(DB 연결 끊김 등)는 그 자리에서 `ABORTED` 처리. 그 처리도 실패하면(DB가 아직 끊김) 재기동 시 정리
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
| `retries` | 응답을 받지 못해 다시 보낸 횟수 ("전송 오류 재시도"). 대부분 0 |
| `errorReason` | `UNRESOLVED_VARIABLE`(변수 누락, 미전송) / `INVALID_REQUEST`(잘못된 URL·헤더) / `TIMEOUT` / `CONNECTION_FAILED` / `IO_ERROR` / `TLS_ERROR`(인증서 만료·신뢰할 수 없는 발급자·이름 불일치·TLS 협상 실패) / `BLOCKED_DESTINATION`(링크 로컬·클라우드 메타데이터 주소, 미전송) / `UNEXPECTED` |

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
  - 클라이언트 재시도 끔. 기본 설정은 429·503(`Retry-After`)과 일부 전송 오류를 다시 보내 첫 응답이 가려짐. 응답을 받지 못한 경우만 앱이 따로 재시도하고 기록 (아래 "전송 오류 재시도")
  - 압축 요청(`Accept-Encoding`) 없음, 쿠키 저장·자동 전송 없음 (케이스에 적은 헤더만 전송)
  - 기본 `User-Agent`는 `Sentinel/<빌드 버전>` (라이브러리 기본값 `Apache-HttpClient/… (Java/…)`은 보안 장비가 자동화 도구로 막고 Java 버전을 알려 줌). 케이스에 적은 값이 있으면 그 값. 알림 Webhook·생존 신호도 같은 값
  - `Accept`는 케이스에 적은 경우만 (없으면 HTTP 규칙상 "아무 형식")
  - 인증서·TLS 연결 수립 실패는 `TLS_ERROR`로 따로 기록 (일반 전송 오류 `IO_ERROR`와 구분, 재시도 안 함). IPv4·IPv6 주소가 모두 있는 이름은 HTTP 클라이언트가 TLS 실패 뒤 다음 주소로 다시 연결하는데, 다음 주소도 실패하면 앞 주소의 TLS 실패로 기록 (다음 주소의 TLS가 성공하면 그 뒤 결과대로)
  - 본문은 실제 길이의 `Content-Length`로 전송 (조각 전송 `chunked` 아님). 케이스에 직접 적은 `Content-Length`는 실제 길이로 바꿈 (다르면 서버가 나머지를 기다리다 시간 초과)
- 연결 재사용. 1초 이상 쉰 연결은 사용 전 확인, 30초 이상 쉰 연결은 정리. 동시 연결 한도 200 (Tomcat 기본 요청 스레드 수)
- 제한 시간: 연결 `sentinel.runner.http.connect-timeout`(기본 5초) 초과 시 `CONNECTION_FAILED`, 응답 대기 `read-timeout`(기본 30초) 초과 시 `TIMEOUT`
  - `read-timeout`은 읽을 때마다 다시 재므로, 본문을 조금씩 늦게 보내는 응답은 끝나지 않을 수 있음. 본문을 다 받을 때까지의 전체 제한 `total-timeout`(기본 2분)을 넘으면 연결을 끊고 `TIMEOUT`
  - 한계: 전체 제한은 본문을 읽는 동안 확인. 응답 헤더를 조금씩 늦게 보내는 경우는 읽기 한 번마다 `read-timeout`만 적용
- 응답 본문 크기: `max-response-size`(기본 10MB)까지만 받고 넘는 부분은 받지 않고 연결을 끊음 (메모리 보호). 판정·추출은 받은 부분으로, 저장은 다시 64KB까지
  - 남은 본문을 읽지 않고 연결을 버림 (닫기만 하면 HTTP 클라이언트가 연결을 다시 쓰려고 남은 본문을 끝까지 읽음)
- 수동 실행은 실행마다 가상 스레드 하나에서 진행 (대부분 GW 응답 대기이므로 스레드를 점유하지 않음). 동시 수동 실행 수는 `sentinel.runner.manual.max-concurrent`(기본 4, 1 미만이면 기동 실패)

### 전송 오류 재시도

GW·네트워크가 잠깐 흔들린 것만으로 실패가 기록·알림되지 않도록, 응답을 받지 못한 케이스는 잠시 기다렸다가 다시 보냄

| 오류 | 재시도 |
|------|--------|
| `CONNECTION_FAILED` (연결 실패·연결 시간 초과) | 모든 메서드. 요청이 서버에 닿지 않음 |
| `TIMEOUT`·`IO_ERROR` (응답 대기 시간 초과·연결 끊김) | `GET`·`HEAD`·`OPTIONS`만. 서버가 이미 처리했을 수 있어 등록·삭제 요청은 두 번 보내지 않음 |
| `BLOCKED_DESTINATION`, `TLS_ERROR`, 전송 전 오류(`UNRESOLVED_VARIABLE`·`INVALID_REQUEST`) | 없음. 다시 보내도 같음 |

- 응답을 받은 뒤의 판정 실패(`FAILED`)는 재시도하지 않음 (실제 문제일 가능성이 큼)
- 설정: `sentinel.runner.retry.max-retries`(기본 1, 0~3, 0이면 끔), `sentinel.runner.retry.delay`(기본 1초)
- 결과는 재시도 뒤의 응답 또는 마지막 오류. 다시 보낸 횟수는 결과의 `retries`(`test_result.retry_count`, V20). 소요 시간은 마지막 전송 기준
- 재시도 뒤에도 응답이 없어 실패 알림을 보내면 사유 끝에 "(N회 다시 보낸 뒤)"
- 재시도한 케이스는 INFO 로그 1줄 (`Run 12 case 5 was sent 1 more time(s) after no response: responded`)

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
- 로그인 없이 허용하는 요청은 메서드와 경로가 정확히 맞을 때만 예외: 로그인(`POST /api/sessions`), 초대·재설정 링크 확인(`GET /api/invites/{token}`·`GET /api/password-resets/{token}`), 계정 만들기(`POST /api/users`, 초대 토큰), 재설정 링크로 비밀번호 설정(`PUT /api/users/{id}/password`, 재설정 토큰). 같은 경로의 다른 메서드는 인증 필요
  - 점 세그먼트, `;`, 퍼센트 인코딩(`%`), `//`가 있으면 인증 요구 (라우팅의 경로 정리 방식에 기대지 않음). 인증 필터는 `/api/*`에만 등록되며 필터에 온 요청은 원문 경로와 관계없이 API로 처리 (`/%61pi/...`처럼 글자를 인코딩한 우회 방지)

### API 토큰 (CI 연동)

배포 파이프라인 등 자동화에서 실행을 시작하고 결과를 확인하는 용도. 로그인 세션은 만료가 짧아(12h) 자동화에 맞지 않음

| API | 설명 |
|-----|------|
| `POST /api/access-tokens` | 발급. 본문 `{"name": "GitHub Actions 배포 확인", "validityDays": 90}`. 201 + `{"token": "snt_...", "accessToken": {...}}`. 토큰 원문은 이 응답에서만 |
| `GET /api/access-tokens` | 내 토큰 목록 (폐기되지 않은 것, 만료 포함, 최근 발급 순). `{id, name, prefix, createdAt, expiresAt, lastUsedAt, expired}` |
| `GET /api/access-tokens/{id}` | 내 토큰 단건 (목록 항목과 같은 형식). 다른 사용자의 토큰·폐기된 토큰은 404 |
| `DELETE /api/access-tokens/{id}` | 폐기. 이후 그 토큰의 요청은 401. 다른 사용자의 토큰은 404 |

- 토큰으로 허용하는 요청은 `POST /api/runs`(실행 시작)와 `GET /api/runs/{id}`(진행 상황·결과)뿐. 그 외 API는 403 (`access tokens can only start runs and read them`). 토큰이 새도 케이스·환경 수정, 환경 변수 조회, 사용자 관리, 토큰 발급은 불가
- 토큰 관리 API 자체는 로그인 세션으로만. 관리자도 다른 사용자의 토큰은 보거나 폐기할 수 없음
- 형식 `snt_` + 무작위 256비트(base64url 43자). 접두어로 세션 토큰과 구분하고 유출 검사 도구가 알아볼 수 있게 함. SHA-256 해시로만 저장하고 목록에는 앞 8자(`prefix`)만
- 유효 기간은 발급 때 30·90·180·365일 중 선택 (만료 없는 토큰 없음, 그 외 400). 만료 시각이 지나면 401. 이름은 1~100자
- 토큰으로 시작한 실행은 발급한 사용자가 실행한 것으로 기록 (`triggeredBy`). 사용할 때마다 `lastUsedAt` 갱신. 사용자를 삭제하면 그 사용자의 토큰도 401
- 발급·폐기는 변경 이력(`ACCESS_TOKEN`, 이름만)에 기록
- 화면: 사이드바 아래 계정 영역의 "API 토큰" (`/settings/access-tokens`). 발급 직후 한 번만 표시

#### 네트워크 경로

Spring은 같은 PC(`127.0.0.1:8080`)에서만 열려 있고 web의 BFF 프록시는 조회만·쿠키 세션만 받으므로, HTTPS 앞단(Caddy)이 토큰 요청만 Spring으로 직접 전달 (`proxy/Caddyfile`)

- `Authorization: Bearer snt_…` 헤더가 있는 `/api/runs`, `/api/runs/*` 요청 → `sentinel-api:8080` (`SENTINEL_API_UPSTREAM`). 그 외는 지금처럼 web
- 토큰 권한은 Spring이 다시 확인 (허용 외 경로·메서드는 403). 브라우저는 다른 사이트에서 `Authorization` 헤더를 붙여 보낼 수 없어(CORS 사전 요청 미허용) 쿠키로 인증되는 쓰기 경로는 생기지 않음
- 헤더는 `Bearer`(대소문자 그대로)로 보내야 함. 다르게 쓰면 web으로 전달되어 401

#### CI에서 실행하고 결과 기다리기

`scripts/sentinel-run.sh`: 실행을 시작하고 끝날 때까지 기다림. 모두 통과하면 0, 판정 실패·오류가 있으면 1(실패한 케이스 목록 출력), 실행하지 못했거나(토큰·권한·없는 스위트·동시 실행 한도) 중지·중단·시간 초과면 2. bash·curl·jq 필요

```bash
SENTINEL_URL=https://sentinel.example.com SENTINEL_TOKEN=snt_... \
  scripts/sentinel-run.sh --suite 3 --environment 2 --timeout 600 --interval 5
```

- 스위트 ID는 스위트 화면 주소(`/suites/3`)의 번호, 환경 ID는 프로젝트 화면 환경 이름 옆의 `#번호`
- 내부 인증 기관(Caddy `internal`) 인증서를 쓰면 `SENTINEL_CA_CERT`에 그 루트 인증서 경로. 기관 발급 인증서처럼 러너가 이미 신뢰하면 생략
- `SENTINEL_URL`은 `https://`만 허용 (토큰이 평문으로 전송되지 않도록). `http://`는 같은 PC(`localhost`·`127.0.0.1`·`[::1]`)만, 그 외는 요청 전에 종료 코드 2
- 토큰은 표준 입력의 curl 설정으로 넘겨 프로세스 목록·출력에 남기지 않음. 시간 초과여도 실행은 서버에서 계속됨
- `--junit 파일`: 끝난 실행의 결과를 JUnit XML로 저장 (통과·실패·중지 모두, 시간 초과는 저장하지 않음). 받지 못하면 경고만 하고 종료 코드는 실행 결과 기준
- 테스트: `bash scripts/sentinel-run.test.sh` (흉내 낸 API 서버로 시나리오별 종료 코드·출력 확인, CI의 scripts 작업)

#### JUnit XML

`GET /api/runs/{id}`에 `Accept: application/xml`이면 결과를 JUnit XML로 응답 (지정하지 않거나 `*/*`면 지금처럼 JSON). 주소가 같아 API 토큰으로도 조회 가능. CI 도구(GitHub Actions의 테스트 결과 액션, Jenkins, GitLab)가 결과를 표로 보여 주는 용도

- 실행 1건 = `testsuite` 1개 (이름 `스위트 (환경)`, 시작 시각 `timestamp`), 케이스 = `testcase` (`classname`은 스위트 이름, `time`은 소요 초)
- 판정 실패 = `failure` (실패한 규칙, 첫 규칙이 `message`), 오류 = `error` (`type`은 사유, 재시도했으면 "(N회 다시 보낸 뒤)")
- `properties`: 실행 ID·상태·환경, 결과 화면 주소(`sentinel.notification.web-base-url` 지정 시)
- 요청·응답 원문은 넣지 않음 (CI 로그·산출물은 볼 수 있는 사람이 더 많음). 판정 메시지는 저장 시 이미 시크릿을 가린 값. XML에 넣을 수 없는 제어 문자는 제거

GitHub Actions 예시. Sentinel이 내부망에 있으면 내부망에 둔 러너(self-hosted)에서 실행 (GitHub 호스팅 러너는 내부 주소에 닿지 않음). 스크립트는 배포하는 저장소에 복사해 두고 사용

```yaml
deploy-check:
  needs: deploy
  runs-on: [self-hosted]
  steps:
    - uses: actions/checkout@v4
    - name: Sentinel 스모크 검사
      env:
        SENTINEL_URL: https://sentinel.example.com
        SENTINEL_TOKEN: ${{ secrets.SENTINEL_TOKEN }}
        SENTINEL_CA_CERT: /etc/ssl/sentinel-root.crt
      run: bash scripts/sentinel-run.sh --suite 3 --environment 2 --timeout 600 --junit sentinel.xml
    # 실패해도 결과 표를 남기도록 always(). JUnit XML을 읽는 액션은 쓰는 것으로 (예: dorny/test-reporter)
    - name: Sentinel 결과 표
      if: always()
      uses: dorny/test-reporter@v1
      with:
        name: Sentinel
        path: sentinel.xml
        reporter: java-junit
```
### 사용자 관리

| API | 권한 | 설명 |
|-----|------|------|
| `GET /api/users` | 관리자 | 사용자 목록 |
| `GET /api/users/{id}` | 관리자 | 사용자 단건 (목록 항목과 같은 형식). 삭제된 사용자는 404 |
| `POST /api/users` | 없음 (초대 토큰) | 초대 수락으로 계정 만들기. 본문 `{"inviteToken": "...", "name": "...", "password": "..."}`. 이메일·역할은 초대에 따름. 201 + `Location` + 사용자. 로그인은 `POST /api/sessions`로 따로 |
| `DELETE /api/users/{id}` | 관리자 | 삭제(보관). 세션·남은 링크 정리 |
| `PUT /api/users/me/password` | 본인 | 비밀번호 변경. 본문 `{"currentPassword": "...", "newPassword": "..."}`. 204 |
| `PUT /api/users/{id}/password` | 없음 (재설정 토큰) | 재설정 링크로 새 비밀번호 설정. 본문 `{"resetToken": "...", "password": "..."}`. 204. 다른 사용자의 링크는 404 |
| `POST /api/invites` | 관리자 | 초대 링크 발급. 본문 `{"email": "...", "role": "MEMBER"}`. 201 + `Location` + `{"token": "...", "expiresAt": "..."}` |
| `GET /api/invites/{token}` | 없음 | 초대 링크 확인 (대상 이메일) |
| `GET /api/pending-invites` | 관리자 | 대기 중인 초대 (수락·만료·취소 전), 최근 발급 순. `[{id, email, role, createdAt, expiresAt, invitedBy(발급자 이메일)}]`. 링크(토큰)는 발급 응답에서만 볼 수 있어 없음 |
| `GET /api/pending-invites/{id}` | 관리자 | 대기 중인 초대 1건. 없거나 수락·만료·취소된 초대는 404 |
| `DELETE /api/pending-invites/{id}` | 관리자 | 초대 취소. 204. 보낸 링크는 바로 확인·수락 모두 404. 없거나 수락·만료·취소된 초대는 404 |
| `POST /api/password-resets` | 관리자 | 재설정 링크 발급. 본문 `{"userId": 7}`. 201 + `Location` + `{"token", "expiresAt"}` |
| `GET /api/password-resets/{token}` | 없음 | 재설정 링크 확인. `{user: {id, name(이메일)}, expiresAt}` |

- 가입 경로는 초대뿐. 공개 가입·비밀번호 찾기 폼 없음 (메일 발송 미사용)
- 링크 토큰은 1회용이며 저장은 해시로만. 만료·사용·위조는 모두 404로 동일하게 응답
  - 사용 기록은 조건부 갱신(아직 안 썼고 만료 전일 때만). 같은 링크로 동시에 요청하면 하나만 성공하고 나머지는 404. 수락 직전에 취소된 초대도 404 (계정은 만들어지지 않음)
- 비밀번호는 10자 이상. 변경·재설정 시 해당 사용자의 모든 세션 종료
- 재설정 링크로 비밀번호를 바꾸면(계정 복구) 그 사용자의 API 토큰도 모두 폐기. 본인 비밀번호 변경은 CI가 끊기지 않도록 API 토큰 유지 (필요하면 "API 토큰" 화면에서 폐기)
- 대기 중인 초대 관리는 `/api/invites/{token}`(로그인 없이 열린 링크 확인)과 겹치지 않도록 숫자 ID를 쓰는 `/api/pending-invites`로 분리
- 초대를 수락하면 같은 이메일로 보낸 다른 초대는 무효. 사용자를 삭제하면 그 이메일로 남은 초대도 삭제 (예전 링크로 다시 가입하지 못하도록)
- 자기 자신 삭제와 마지막 관리자 삭제는 400으로 거부
- 만료된 로그인 세션과 초대·재설정 링크(사용 여부와 무관)는 매일 정리 배치(`sentinel.retention.cron`, `sentinel.retention.enabled`)에서 삭제. 만료되면 쓰이지 않으므로 동작은 같음. 로그: `Deleted N expired session(s) and M expired invite/password reset link(s)`. API 토큰은 대상 아님
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

- 동시 수정: 스케줄·환경·케이스는 낙관적 잠금(`version`, V30). 같은 항목을 다른 요청이 먼저 고쳤으면 409 `modified by another request; reload and try again` (이름 중복 409와 문구로 구분). 폴러의 다음 실행 시각 갱신과 사용자의 끄기·삭제가 겹치면 사용자 변경을 남기고 다음 주기에 다시 판단
  - 저장 시점 확인은 동시에 겹친 요청만 막음. 수정 화면을 연 뒤 다른 곳에서 고친 경우까지 막으려면 조회 응답의 `version`을 `PATCH` 본문에 함께 보냄 (환경·케이스). 다르면 같은 409, 생략하면 확인하지 않음 (기존 호출 호환)
  - `PATCH` 응답의 `version`은 저장 후 값이라 이어서 수정할 때 그대로 사용
  - 스케줄은 화면 version 확인 대상 아님: 폴러가 실행할 때마다 다음 실행 시각을 고쳐 version이 올라가므로, 매분 실행 스케줄은 수정 화면을 1분만 열어 둬도 저장할 수 없게 됨. 저장 시점 확인만 적용
- 응답에서 다른 리소스를 가리킬 때는 `{id, name}` 객체 (예: 환경·스위트의 `project`, 케이스의 `suite`, 스케줄의 `project`·`suite`·`environment`). 요청 본문과 조회 조건은 ID (`projectId`, `suiteId` 등)

| 리소스 | 목록 | 단건 | 생성·수정 | 삭제 |
|--------|------|------|-----------|------|
| 프로젝트 | `GET /api/projects` | `GET /api/projects/{id}` | `POST /api/projects`, `PATCH /api/projects/{id}` | `DELETE /api/projects/{id}` |
| 환경 | `GET /api/environments?projectId=` | `GET /api/environments/{id}` | `POST /api/environments`, `PATCH /api/environments/{id}` | `DELETE /api/environments/{id}` |
| 스위트 | `GET /api/suites?projectId=` | `GET /api/suites/{id}` | `POST /api/suites`, `PATCH /api/suites/{id}` | `DELETE /api/suites/{id}` |
| 케이스 | `GET /api/cases?suiteId=&q=` | `GET /api/cases/{id}` | `POST /api/cases`, `PATCH /api/cases/{id}` | `DELETE /api/cases/{id}` |
| 실행 | `GET /api/runs?projectId=&suiteId=&environmentId=&status=&from=&to=&failing=&page=0&size=20` | `GET /api/runs/{id}` | `POST /api/runs`, `PATCH /api/runs/{id}`(중지) | |
| 스케줄 | `GET /api/schedules?suiteId=` | `GET /api/schedules/{id}` | `POST /api/schedules`, `PATCH /api/schedules/{id}`, `PATCH /api/schedules`(여러 건) | `DELETE /api/schedules/{id}` |
| 점검 시간 | `GET /api/maintenance-windows?environmentId=` | `GET /api/maintenance-windows/{id}`(끝난 점검 포함) | `POST /api/maintenance-windows`, `PATCH /api/maintenance-windows/{id}` | `DELETE /api/maintenance-windows/{id}` |
| 휴일 | `GET /api/holidays?year=` | `GET /api/holidays/{id}` | `POST /api/holidays`, `POST /api/holiday-imports`(여러 건) | `DELETE /api/holidays/{id}` |
| 알림 발송 이력 | `GET /api/notification-deliveries?projectId=&failed=` | `GET /api/notification-deliveries/{id}` | `POST /api/notification-deliveries`(연결 확인 알림) | |
| 알림 채널 | `GET /api/notification-channels` | | | |
| 응답시간 추세 | `GET /api/response-time-trends?caseId=&environmentId=&from=&to=` | | | |
| 응답시간 요약 | `GET /api/response-time-summaries?suiteId=&environmentId=&from=&to=` | | | |
| 응답시간 변화 | `GET /api/response-time-changes?from=&to=&limit=5` | | | |
| 스위트 요약 | `GET /api/suite-summaries?from=&to=` | | | |
| 불안정 케이스 | `GET /api/unstable-cases?from=&to=&suiteId=` | | | |
| 모니터링 공백 | `GET /api/monitoring-gaps?from=&to=` | | | |
| 지금 느린 케이스 | `GET /api/slowdowns?suiteId=` | | | |
| 실패 원인 | `GET /api/failure-causes?from=&to=&suiteId=` | | | |
| 변경 이력 | `GET /api/change-logs?targetType=&targetId=&projectId=&suiteId=&page=0&size=20` | | | |
| 시스템 상태 | | `GET /api/system-status` (관리자, "시스템 상태") | | |

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
- 실행 상세: `project`·`suite`·`environment`(`{id, name}`)와 케이스별 결과(`testCase: {id, name}`), 수동 실행이면 실행 주체 포함
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

### API 문서

`GET /api/openapi`: OpenAPI 3.1 문서(JSON). 다른 API와 같이 로그인 필요 (API 토큰은 403). 화면은 web의 "API 문서"

- 경로·요청·응답 모양은 컨트롤러에서, 설명은 컨트롤러 메서드 Javadoc(요약·설명)과 응답 레코드 Javadoc(`@param`, 필드 설명)에서 생성 (springdoc-openapi + therapi-runtime-javadoc)
- 묶음(태그)은 컨트롤러의 `@Tag`, 순서·설명은 `OpenApiConfig.TAGS`. 로그인 전에 쓰는 요청은 `@SecurityRequirements`로 인증 표시 없음
- 새 API는 `@Tag`(알려진 묶음)와 메서드 Javadoc이 있어야 함 (`OpenApiDocsTest`가 확인)
- 응답 상태: `ResponseEntity.created`·`noContent`·`accepted`를 돌려주는 메서드는 `@ResponseStatus`(201·204·202)도 함께 지정. 없으면 문서에 200으로 표시됨
- 응답 형식은 `application/json` (`springdoc.default-produces-media-type`). 지정하지 않으면 `*/*`로 표시
- 기동 로그의 springdoc "운영에서는 끄라"는 경고는 로그 수준으로 숨김. 로그인 뒤에서만 제공하므로 의도한 설정

### 응답시간 추세

저장된 실행 결과에서 p50·p95·최대 응답시간을 바로 집계 (별도 집계 테이블 없음)

| API | 설명 |
|-----|------|
| `GET /api/response-time-trends?caseId=&environmentId=` | 케이스 1건의 일별 값. 기간 생략 시 최근 30일. `{testCase, environment, from, to, points}` |
| `GET /api/response-time-summaries?suiteId=&environmentId=` | 스위트의 케이스별 값 (기간 전체). 기간 생략 시 최근 7일. `{suite, environment, from, to, cases: [{testCase, samples, p50, p95, max}]}` |
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

### 불안정 케이스

`GET /api/unstable-cases?from=&to=&suiteId=`: 기간 안 스케줄 실행에서 실패했다 통과했다를 반복하거나 응답이 없어 다시 보낸 일이 잦은 케이스·환경. 기간 생략 시 최근 7일, 최대 90일. `suiteId`는 그 스위트만

| 기준 | 설명 |
|------|------|
| 실패 구간 2회 이상 | 통과(또는 기간 첫 실행)에서 판정 실패·오류로 바뀐 횟수. 실패가 이어지다 복구된 한 번의 장애는 1이라 제외 |
| 재시도한 실행 2회 이상 | 응답이 없어 다시 보낸 실행 수 ("전송 오류 재시도"). 재시도로 통과해 가려진 흔들림 포함 |

- 항목: `testCase`·`suite`·`environment`(`{id, name}`), 기간 안 스위트·환경의 스케줄 실행 수 `runs`, 실패 수 `failures`, 실패 구간 수 `failureEpisodes`, 재시도한 실행 수 `retriedRuns`
- 실패 구간·재시도가 많은 순. 환경마다 따로 판단 (dev와 staging에서 번갈아 실패해도 각각 1)
- 스케줄 실행(완료)만. 수동 실행은 수정 확인용이 많아 제외. 삭제된 케이스·환경은 제외
- 결과 테이블 전체를 읽지 않음: 실행 행의 판정 수(V10)·재시도 수(`test_run.retried_count`, V22)로 실패·재시도가 있던 실행만 골라 그 결과만 읽음 (1년치 최악 분포 30일 362ms, 결과 전체를 읽으면 1.2초)

### 실패 원인

`GET /api/failure-causes?from=&to=&suiteId=`: 기간 안 스케줄 실행의 실패·오류 결과를 원인별로. 알림이 왔을 때 GW·네트워크 문제인지, API 문제인지, 케이스·환경 설정 문제인지 먼저 가름. 기간 생략 시 최근 7일, 최대 90일. `suiteId`는 그 스위트만

| 묶음 | 원인 (`cause`) | 기준 |
|------|------|------|
| `NETWORK` 응답 없음 | `TIMEOUT` 타임아웃 | 응답 대기 시간 초과 |
| | `CONNECTION_FAILED` 연결 실패 | 연결 거부·이름 해석 실패 등 |
| | `CONNECTION_LOST` 연결 끊김 | 주고받는 중 끊김 (`IO_ERROR`) |
| | `TLS` 인증서·TLS 오류 | 인증서 만료·신뢰할 수 없는 발급자·이름 불일치, TLS 협상 실패 (`TLS_ERROR`) |
| `API` 응답은 받음 | `SERVER_ERROR` 서버 오류 | 상태 코드 5xx |
| | `CLIENT_ERROR` 요청 거부 | 상태 코드 4xx (인증·요청 형식·API 변경) |
| | `ASSERTION_MISMATCH` 응답 내용 불일치 | 그 외 상태 코드에서 판정 규칙 실패 (본문·JsonPath·헤더·응답 시간) |
| `SETUP` 요청 못 만듦 | `CONFIGURATION` 설정 오류 | 없는 변수, 잘못된 요청, 막힌 목적지 |
| `OTHER` | `OTHER` 기타 | Sentinel 내부 오류. 있을 때만 |

- 항목: 원인, 묶음, 결과 수 `results`, 케이스·환경 수 `caseCount`, 결과가 많은 케이스·환경 3건 `cases`(`testCase`·`suite`·`environment`, `results`)
- 결과가 많은 원인 순, 결과가 없는 원인은 생략. 전체 실패·오류 결과 수는 `results`
- 스케줄 실행(완료)만. 수동 실행은 수정 확인용이 많아 원인 비율이 왜곡됨 (불안정 케이스와 같은 기준). 삭제된 케이스·환경 제외
- 응답 시간 판정만 실패한 경우도 응답 내용 불일치 (판정 결과 JSON을 읽지 않고 기존 기록인 오류 사유·상태 코드만으로 집계)
- 실패가 있던 실행만 골라 그 실행의 결과만 읽음 (불안정 케이스와 같은 방식). 45일치(결과 129만 건, 실행의 약 34%에 실패) 7일 29ms, 30일 200ms

### 케이스 가져오기 (OpenAPI 3, Postman)

문서를 케이스 후보로 변환해 확인한 뒤, 고른 후보만 가져오는 2단계 방식

| API | 설명 |
|-----|------|
| `POST /api/case-import-previews` | 변환만 하고 저장하지 않음. 본문 `{"suiteId": 1, "format": "OPENAPI_3", "content": "<문서 원문>"}`. `format`은 `OPENAPI_3`(JSON·YAML) 또는 `POSTMAN`(Collection v2.0·v2.1 JSON) |
| `POST /api/case-imports` | 한 트랜잭션으로 등록. 본문 `{"suiteId": 1, "cases": [{"name": "...", "request": {...}, "assertions": [...]}]}`. 201 + `{"suite": {id, name}, "cases": [등록한 케이스]}` |

- 미리보기 응답: `title`, `version`, `servers`(환경 기본 URL 설정 참고용), `cases`, `warnings`(문서 단위)
  - 후보: `source`(원본 위치. 예: `GET /pets/{petId}`, `GET {{baseUrl}}/orders`), `name`, `request`, `assertions`, `warnings`(생략·대체한 내용), `conflict`(스위트에 같은 이름 있음)
  - 경고: `{"code": "COOKIE_PARAMETER", "params": {"name": "session"}, "message": "required cookie parameter 'session' is not supported"}`. 화면은 `code`·`params`로 한국어 문장을 만들고(`web/lib/import-message.ts`) 모르는 `code`는 영어 `message` 그대로. 종류와 `params` 이름은 `ImportWarning.Code`
  - 후보를 그대로(또는 이름·요청을 고쳐) `cases`로 보내면 가져오기. `source`·`warnings`·`conflict`는 무시
- 가져오기는 한 건이라도 실패하면 전부 미등록. 이름 중복(스위트 기존 케이스, 목록 안 중복)은 409, 잘못된 케이스는 400 (`cases[1] 'Broken': ...`처럼 위치 표시)
- 문서는 5MB(문자 수)까지, 한 번에 가져오기는 500건까지
- Swagger 2.0 등 OpenAPI 3이 아닌 문서, Postman v1 컬렉션·환경 파일, 해석할 수 없는 문서, 작업·요청이 없는 문서는 400
  - 이 400 응답에는 `detail`(영어)과 함께 `code`·`params`. 예: `{"detail": "only OpenAPI 3 documents are supported (found swagger 2.0)", "code": "SWAGGER_2", "params": {"version": "2.0"}}`. 화면은 `code`로 한국어 문장(`web/lib/import-message.ts`), 모르는 `code`는 `detail` 그대로. 종류와 `params` 이름은 `ImportDocumentException.Code`. 그 밖의 400(잘못된 케이스 등)에는 `code` 없음

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
| `http` `bearer`, `oauth2`, `openIdConnect` | `Authorization: Bearer {{스킴 이름}}`. 고정 토큰은 시크릿으로, 로그인 API가 발급하는 토큰(만료됨)은 앞 케이스의 값 추출로 같은 이름의 변수에 받음 |
| `http` `basic` | `Authorization: Basic {{스킴 이름}}`. 값은 `아이디:비밀번호`를 Base64로 인코딩한 문자열 |
| `apiKey` header·query | `X-API-Key: {{스킴 이름}}`, `?tenant={{스킴 이름}}`처럼 지정한 이름·위치에 추가 |
| `apiKey` cookie, 그 밖의 `http` 스킴(digest 등) | 미지원 경고 |

- 사용한 스킴마다 "어떤 변수를 시크릿으로 등록해야 하는지" 문서 단위 안내 1건 (`warnings`). 토큰 스킴(bearer·oauth2·openIdConnect)은 로그인 케이스에서 값 추출로 받는 방법도 함께. Postman의 bearer·oauth2 토큰도 같음

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
  - 키 교체: 새 키를 `SENTINEL_SECRET_KEY`, 이전 키를 `SENTINEL_SECRET_KEY_PREVIOUS`(`sentinel.secret.previous-key`)로 지정 후 기동. 절차는 `docs/deploy.md` "암호화 키 교체"
    - 기동 시(요청·스케줄 시작 전) 이전 키로 저장된 환경 시크릿·프로젝트 Webhook 주소를 새 키로 다시 암호화. 보관(삭제)된 항목 포함, 변경 이력에는 남기지 않음
    - 이전 키가 있는 동안은 두 키 모두로 복호화, 암호화는 항상 새 키. 저장 형식(`v1:`)은 그대로이며 GCM 인증 태그가 다른 키의 복호화를 거부하므로 키를 차례로 시도
    - 기동 로그: 다시 암호화한 수(`Re-encrypted N secret value(s)`). 어느 키로도 풀 수 없는 값은 지우지 않고 위치만 경고(`environment 3 secret 'token'`, `project 2 webhook`). 값은 로그에 없음
    - 이전 키만 있고 새 키가 없으면 기동 실패. 이전 키가 새 키와 같으면 무시
  - 이전 키 없이 키만 바꾸면 기존 시크릿을 읽지 못하므로 해당 환경의 실행이 400으로 실패. 기동 로그에 풀 수 없는 값의 위치를 경고

- 삭제는 소프트 삭제(보관). 204 응답, 하위 항목 연쇄 보관, 실행 이력은 유지
  - 프로젝트 → 환경·스위트·케이스·스케줄, 환경 → 해당 스케줄, 스위트 → 케이스·스케줄
  - 보관된 항목은 목록에서 제외되고 단건 조회·재삭제·실행은 404. 같은 이름으로 새로 등록 가능

## 시스템 상태

`GET /api/system-status`: 서버 설정·버전 상태 (관리자, 그 외 403). 설정은 지정 여부만, 주소·키 값은 포함하지 않음

```json
{
  "build": {"version": "1.0.0", "builtAt": "2026-10-10T07:36:42Z"},
  "startedAt": "2026-10-10T07:40:03Z",
  "databaseMigration": "29",
  "secrets": {
    "state": "ROTATING",
    "undecryptable": [
      {"project": {"id": 2, "name": "결제"}, "environment": null, "secret": null},
      {"project": {"id": 2, "name": "결제"}, "environment": {"id": 3, "name": "dev"}, "secret": "token"}
    ]
  },
  "defaultChannel": true,
  "resultLinks": true,
  "heartbeat": false,
  "retention": {"rawResultDays": 30, "notificationDeliveryDays": 90}
}
```

| 항목 | 내용 |
|------|------|
| `build` | Gradle 빌드 정보(`springBoot.buildInfo()`)의 버전·빌드 시각. 빌드 정보 없이 실행하면(IDE) null |
| `startedAt` | 기동 완료 시각 |
| `databaseMigration` | 적용된 마지막 Flyway 마이그레이션 버전 |
| `secrets.state` | `DISABLED`(키 없음), `ENABLED`, `ROTATING`(이전 키 설정, 재암호화 확인 후 제거) |
| `secrets.undecryptable` | 지금 키로 풀 수 없는 값. 프로젝트 Webhook 주소(`environment`·`secret` null)가 먼저, 환경 시크릿은 이름까지. 사용 중인 항목만, 조회할 때마다 다시 확인 |
| `defaultChannel`·`resultLinks`·`heartbeat` | `SENTINEL_WEBHOOK_URL`·`SENTINEL_WEB_URL`·`SENTINEL_HEARTBEAT_URL` 지정 여부 |
| `retention` | 결과 원문·알림 발송 이력 보관 일수 |

## 변경 이력

설정을 누가·언제·무엇을 바꿨는지 기록. 등록·수정·삭제 API가 변경과 같은 트랜잭션에서 남기므로 변경이 거부(400·404·409)되면 기록도 없음

| 대상 (`targetType`) | 기록 시점 | 변경한 사용자 (`actor`) |
|------|------|------|
| `PROJECT`·`ENVIRONMENT`·`SUITE`·`CASE`·`SCHEDULE`·`MAINTENANCE_WINDOW` | 등록·수정·삭제 (케이스 가져오기는 케이스마다 등록) | 요청한 사용자 |
| `ACCESS_TOKEN` | API 토큰 발급(`CREATED`)·폐기(`DELETED`) (대상 이름은 토큰 이름) | 토큰 주인 |
| `INVITE` | 초대 링크 발급(`CREATED`)·취소(`DELETED`) (대상 이름은 이메일) | 관리자 |
| `HOLIDAY` | 휴일 등록·삭제 (대상 이름은 "날짜 이름", 예: `2026-10-09 한글날`) | 관리자 |
| `USER` | 가입(초대 수락), 비밀번호 변경·재설정, 재설정 링크 발급, 삭제 | 가입·비밀번호는 본인, 링크 발급·삭제는 관리자 |

- 항목: `{id, occurredAt, actor: {id, name(이메일)}, target: {type, id, name}, project: {id, name}, suite: {id, name}, action, fields}`
  - `action`: `CREATED` / `UPDATED` / `DELETED`
  - `target.name`: 변경 당시 이름. 항목을 삭제해도 표시 가능. 스케줄은 스위트 이름, 점검 시간은 환경 이름
  - `project`: 소속 프로젝트 (지금 이름, 삭제된 프로젝트 포함). 사용자·초대·휴일은 `null`
  - `suite`: 소속 스위트. 스위트는 자신, 케이스·스케줄은 소속 스위트, 그 외는 `null`
  - `fields`: 수정에서 값이 실제로 바뀐 항목 이름. 등록·삭제는 빈 목록
- `fields` 값: 프로젝트·스위트 `name`·`description`, 스위트 `caseOrder`, 환경 `name`·`baseUrl`·`variables.<키>`·`secrets.<이름>`, 케이스 `name`·`request`·`assertions`·`extractors`, 프로젝트 `webhook`, 스케줄 `environment`·`cron`·`enabled`·`skipHolidays`, 점검 시간 `startsAt`·`endsAt`·`reason`, 사용자 `password`·`passwordResetLink`
- 값은 남기지 않음 (변수 값, 시크릿, 비밀번호, 링크 토큰 모두). 시크릿은 암호화 값을 비교할 수 없어 요청에 있던 이름을 모두 변경으로 기록
- 같은 값으로 저장한 수정은 기록하지 않음. 삭제는 대상 1건만 기록 (프로젝트 삭제 시 함께 보관되는 환경·스위트·케이스·스케줄은 따로 기록하지 않음)
- 스케줄러가 다음 실행 시각을 옮기는 것과 실행 자체는 기록하지 않음 (실행은 실행 이력)
- 조회: 최신순, `size` 1~100. `targetType`+`targetId`는 항목 1건, `projectId`는 프로젝트와 그 안의 항목 전체, `suiteId`는 스위트와 그 안의 케이스·스케줄. 잘못된 `targetType`은 400
- 이 기능 이전(V13 전)의 변경은 기록이 없음

## 스케줄 실행

| API | 설명 |
|-----|------|
| `POST /api/schedules` | 스케줄 등록. 본문 `{"suiteId": 1, "environmentId": 1, "cron": "10 9 * * 1-5", "skipHolidays": true}`. `skipHolidays` 생략 시 `false`. 201 + `Location` |
| `GET /api/schedules/{id}` | 조회. `{id, project, suite, environment, cron, zone, enabled, skipHolidays, nextRunAt(다음 실행 시각), lastTriggeredAt(마지막 실행 시각)}`. 관계는 `{id, name}` |
| `PATCH /api/schedules` | 여러 스케줄 한 번에 변경. 본문 `{"scheduleIds": [1, 2], "enabled": false, "skipHolidays": true}` (`enabled`·`skipHolidays` 중 하나 이상, 생략한 항목은 유지). 200 + 바뀐 스케줄 목록(요청 순서, 중복 ID는 한 번) |
| `PATCH /api/schedules/{id}` | 부분 수정. `{"enabled": false}`는 `nextRunAt` 제거, `{"enabled": true}`는 현재 시각 기준 재계산. `{"cron": "30 17 * * 1-5"}`는 활성 상태면 `nextRunAt` 재계산. `{"environmentId": 2}`는 실행 환경 변경(스위트와 같은 프로젝트만, 다르면 400). `{"skipHolidays": true}`는 휴일 건너뛰기 켜기. 생략한 필드는 유지 |

- cron은 5자리 표준 형식(`분 시 일 월 요일`), `Asia/Seoul` 기준. 예: `10 9 * * 1-5` = 평일 09:10
- 60초 간격 폴링으로 실행 시각 도달 스케줄의 스위트 실행 (`trigger: SCHEDULED`)
- 노트북 절전 등으로 놓친 실행은 1회만 실행 후 다음 시각으로 이동
- 기동 시 `RUNNING`으로 남은 실행은 `ABORTED` 처리. 스케줄 폴링·웹 요청이 시작되기 전(모든 빈 생성 직후)에 하므로 재기동 직후의 따라잡기 실행은 건드리지 않음
- 실행할 스케줄마다 그 차례의 시각으로 점검 시간·휴일·다음 실행 시각을 판단 (앞 스위트가 오래 걸려도 폴링 시작 시각으로 판단하지 않음)
- DB 접속 실패 시 해당 주기 건너뜀. 연결 실패 시 1회, 복구 시 1회 알림
- 예정 시각보다 10분 넘게 늦게 실행된 스케줄이 있으면 모니터링 공백으로 기록·알림 (아래 "모니터링 공백")
- 환경이 점검 중이면 그 환경의 스케줄은 실행하지 않고 다음 실행 시각으로 넘어감 (아래 "점검 시간")
- 휴일 건너뛰기(`skipHolidays`)를 켠 스케줄은 등록한 휴일에 실행하지 않음 (아래 "휴일")
- 여러 건 변경: 1~200건, 한 트랜잭션. 없거나 삭제된 스케줄이 하나라도 있으면 전부 미변경 404. 스케줄마다 변경 이력
  - 이미 그 상태인 스케줄은 그대로: 켜져 있는 스케줄을 다시 켜면 다음 실행 시각이 지금 기준으로 다시 계산되어 곧 돌 실행을 건너뛸 수 있음
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
| `sentinel.schedule.gap-threshold` | `10m` | 모니터링 공백 기준. 폴링 주기와 평소 실행 시간보다 길게 |

### 모니터링 공백

Sentinel 중지·서버 절전·DB 연결 끊김 등으로 스케줄이 제때 실행되지 않은 기간. 이 동안은 장애가 나도 실패 알림이 없으므로 "알림이 없었다 = 정상"이 아님을 알리고 대시보드에 남김

- 감지: 폴링이 재개될 때 예정 시각보다 기준 시간(`gap-threshold`) 넘게 늦은 스케줄이 있으면 기록. 멈춰 있는 동안은 알 수 없으므로 재개 후 알림
- 기간: 늦은 스케줄 중 가장 이른 예정 시각 ~ 폴링 재개 시각. 스케줄이 없거나 하루 1회뿐이면 실제 중지 시각보다 늦게 시작할 수 있음
- 공백이 끝난 뒤 기준 시간 안에 다시 시작된 공백은 한 건으로 합침 (밀린 실행을 처리하느라 다음 폴링이 늦어진 경우 등). 합친 공백은 다시 알리지 않음
- 점검 시간·휴일로 건너뛴 스케줄, 비활성 스케줄은 해당 없음 (다음 실행 시각이 제때 옮겨짐)
- 기록하지 못해도 알림은 발송. DB 연결 복구 알림을 보낸 주기는 같은 기간이므로 공백 알림 생략 (기록은 함)

멈춘 그 순간에 알려면 아래 "외부 생존 신호"

`GET /api/monitoring-gaps?from=&to=`: 기간과 겹치는 공백, 최근 시작 순. 기간 생략 시 최근 7일, 최대 90일

```json
{"from": "2026-09-26", "to": "2026-10-02", "gaps": [{"id": 3, "startedAt": "2026-10-02T00:10:00Z", "endedAt": "2026-10-02T02:40:00Z", "missedSchedules": 2}]}
```

- `missedSchedules`: 예정 시각에 실행되지 않은 스케줄 수. 합친 공백은 가장 많았던 수

### 외부 생존 신호

모니터링 공백 알림은 Sentinel이 다시 켜진 뒤에야 가므로, 멈춘 즉시 알도록 외부 감시 서비스에 신호를 보냄. 신호가 끊기면 외부 서비스가 알림

- 스케줄 폴링(60초)마다 DB 조회가 성공하면 `sentinel.heartbeat.url`로 GET 1회 (스위트 실행 전). DB 접속 실패 중에는 보내지 않음
- healthchecks.io(무료), Uptime Kuma(푸시 모니터), Better Stack(하트비트) 등 GET 신호 주소를 받는 서비스. 내부망에서 그 서비스에 접속할 수 있어야 함
- 외부 서비스의 기간·유예 시간은 1분·10분 이상 권장. 신호는 스위트 실행 시간만큼 늦어질 수 있음 (긴 스위트가 있으면 유예를 그만큼 늘림)
- 주소는 자격증명(주소만 알면 신호를 보내거나 끊을 수 있음). 환경변수로 주입하고 로그·오류 메시지에 남기지 않음. 잘못된 형식이면 기동 실패
- 실패는 처음 1회만 WARN(상태 코드나 `timeout`·`connection failed`), 다시 성공하면 INFO 1회. 폴링은 계속
- 스케줄 폴링(`sentinel.schedule.enabled`)이 꺼져 있으면 보내지 않음

| 설정 | 기본값 | 설명 |
|------|--------|------|
| `sentinel.heartbeat.url` | 없음 (`SENTINEL_HEARTBEAT_URL`) | 신호 주소. 미지정 시 보내지 않음 |
| `sentinel.heartbeat.timeout` | `5s` | 연결·응답 제한 시간 |

### 점검 시간

배포·점검으로 실패가 예상되는 동안 환경의 스케줄 실행을 건너뜀. 실행 기록이 없으므로 실패 알림·스위트 요약·직전 실행 비교에 점검 중 실패가 섞이지 않고, 끝나면 따로 조치 없이 다시 실행

| API | 설명 |
|-----|------|
| `POST /api/maintenance-windows` | 등록. 본문 `{"environmentId": 1, "startsAt": "2026-09-29T10:00:00+09:00", "endsAt": "2026-09-29T11:00:00+09:00", "reason": "GW 배포"}`. 201 + `Location` |
| `GET /api/maintenance-windows?environmentId=` | 끝나지 않은 점검(진행 중·예정), 시작 순. `environmentId` 생략 시 전체. 삭제된 환경의 점검은 제외 |
| `PATCH /api/maintenance-windows/{id}` | 부분 변경(연장·단축·사유). 생략한 필드는 유지, `"reason": ""`은 사유 삭제. 이미 끝난 점검은 409 |
| `DELETE /api/maintenance-windows/{id}` | 삭제. 예정된 점검은 취소, 진행 중인 점검은 바로 끝나 다음 스케줄부터 실행. 204 |

- 항목: `{id, environment: {id, name}, project: {id, name}, startsAt, endsAt, reason, active, createdBy: {id, name(이메일)}}`. `active`는 지금 점검 중(스케줄 건너뜀), `false`는 예정
- 기간: `startsAt` 이상 `endsAt` 미만. `endsAt`은 `startsAt` 뒤이고 현재 이후, 기간은 최대 7일 (끄고 잊어 모니터링이 멈추는 것 방지). 이미 시작한 시각으로도 등록 가능(지금부터 점검). 어기면 400
- `reason`: 선택, 앞뒤 공백 제거 후 200자 이하
- 건너뛴 스케줄은 `nextRunAt`만 다음 시각으로 옮기고 `lastTriggeredAt`(마지막 실행 시각)은 그대로. INFO 로그 1줄
- 겹치는 점검 허용 (하나라도 진행 중이면 건너뜀). 수동 실행은 점검 중에도 가능 (점검 중 상태 확인용)
- 등록·변경·삭제는 변경 이력(`MAINTENANCE_WINDOW`)에 기록 (누가 스케줄을 멈췄는지)

```
Skipped schedule 4 (suite 3) at 2026-09-29T01:10:00Z: environment 2 is under maintenance until 2026-09-29T02:00:00Z
```

### 휴일

공휴일·임시공휴일 등 대상 시스템도 쉬는 날에 스케줄 실행을 건너뜀. 평일 cron(`1-5`)만으로는 거를 수 없는 날용. 스케줄마다 휴일 건너뛰기(`skipHolidays`)를 켠 경우만 해당 (24시간 감시 스케줄은 휴일에도 실행)

| API | 설명 |
|-----|------|
| `GET /api/holidays?year=` | 그해 휴일, 날짜 순. `year` 생략 시 올해(`sentinel.schedule.zone` 기준) |
| `POST /api/holidays` | 등록. 본문 `{"date": "2026-10-09", "name": "한글날"}`. 201 + `Location`. 관리자만 (그 외 403) |
| `DELETE /api/holidays/{id}` | 삭제. 그날도 다시 실행. 204. 관리자만 |
| `POST /api/holiday-imports` | 여러 휴일을 한 번에 등록. 본문 `{"holidays": [{"date": "2026-12-25", "name": "성탄절"}, ...]}`. 201 + `{"created": [등록한 휴일], "skipped": [이미 등록되어 건너뛴 날짜의 기존 휴일]}`. 관리자만 |

- 항목: `{id, date, name}`
- 휴일 판단은 `sentinel.schedule.zone` 기준 날짜 (예: 09:00 KST 스케줄은 UTC로 전날이어도 KST 날짜로 판단)
- `name`: 앞뒤 공백 제거 후 1~100자. 이미 등록한 날짜는 409 (삭제 후 다시 등록 가능)
- 공휴일 자동 반영 없음 (대체공휴일·임시공휴일 포함 화면에서 직접 등록). 모든 스케줄에 영향을 주므로 등록·삭제는 관리자만, 조회는 모든 사용자
- 건너뛴 스케줄은 점검 시간과 같이 `nextRunAt`만 옮김 (실행 기록·알림·모니터링 공백 없음). INFO 로그 1줄. 수동 실행은 휴일에도 가능
- 등록·삭제는 변경 이력(`HOLIDAY`)에 기록
- 한 번에 등록: 1~100건, 한 트랜잭션. 이미 등록한 날짜는 건너뜀 (같은 목록을 다시 보내도 중복 없음). 날짜 없음·목록 안 같은 날짜·잘못된 이름(건너뛸 날짜 포함)이 한 건이라도 있으면 전부 미등록 400 (`holidays[1] 2027-01-01: name must not be blank`처럼 위치 표시). 등록한 휴일마다 변경 이력

```
Skipped schedule 4 (suite 3) at 2026-10-09T00:10:00Z: 2026-10-09 is a holiday
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
| `sentinel.retention.notification-retention` | `90d` | 알림 발송 이력 보관 기간 (아래 "발송 이력"). 같은 시각에 삭제. 0 이하이면 기동 실패 |
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
계속 실패하면 1시간마다 다시 알림 (새로 실패·복구는 바로)
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
| `[Sentinel] {환경} {스위트} - N건 중 M건 실패 (새로 실패 K건)` | 스케줄 실행 결과에 판정 실패(`FAILED`)·오류(`ERROR`) 존재. 모두 이전부터 실패하던 케이스면 `(계속 실패 중)`이고 1시간마다만 발송 (아래 "계속 실패 중 재알림") |
| `[Sentinel] {환경} {스위트} - 복구, N건 모두 통과` | 모두 통과했고 직전 스케줄 실행에 실패가 있었을 때 1회 |
| `[Sentinel] 스케줄 {id} 실행 오류` | 스케줄 실행 자체가 예외로 중단. 같은 스케줄의 같은 사유는 재알림 간격(`sentinel.notification.repeat-interval`, 기본 1시간)마다 한 번 (시크릿을 풀 수 없는 스케줄이 매 주기 알림을 보내지 않도록). 사유가 바뀌거나 정상 실행된 뒤에는 바로 |
| `[Sentinel] DB 연결 실패 - 스케줄 실행 중단` | DB 접속 실패 첫 주기 1회 |
| `[Sentinel] DB 연결 복구 - 스케줄 실행 재개` | 접속 복구 시 1회, 중단 기간 포함 |
| `[Sentinel] 모니터링 공백 - 스케줄이 제때 실행되지 않음` | 폴링 재개 시 기준 시간 넘게 늦은 스케줄이 있을 때 1회. 기간·스케줄 수 포함 (위 "모니터링 공백") |
| `[Sentinel] 주간 요약 MM-dd ~ MM-dd` | 매주 월요일 09:00, 지난주 현황 (아래 "주간 요약") |
| `[Sentinel] {프로젝트} - 응답시간 악화 N건` / `- 응답시간 회복 N건` | 케이스·환경별 p95가 기준 이상 나빠질 때 1회, 돌아올 때 1회 (아래 "응답시간 악화 알림") |

- 실패 케이스마다 `[새로 실패]` 또는 `[N회째 · 시작 시각부터]` 표시, 새로 실패한 케이스가 앞. 케이스 이름과 요청을 함께 표시
- 연속 여부는 이전 스케줄 실행 20회까지 확인. 모두 실패였으면 `[21회 이상 · …]`
- 복구 알림: 직전 실행에서 실패했다가 통과한 케이스 이름(최대 10개), 실패가 이어진 스케줄 실행 수와 기간
- 수동 실행을 비교에서 빼는 이유: 수정 확인용으로 실행하는 경우가 많아, 넣으면 다음 스케줄 실행의 복구 알림이 누락될 수 있음
- 계속 통과하면 발송하지 않음. 시각은 `sentinel.schedule.zone` 기준

### 계속 실패 중 재알림

같은 케이스가 계속 실패하는 동안 스케줄 실행마다 같은 알림이 반복되지 않도록 재알림 간격(`sentinel.notification.repeat-interval`, 기본 1시간)마다만 발송

| 이번 실행 | 발송 |
|-----------|------|
| 새로 실패한 케이스가 있음 (계속 실패 중인 케이스와 함께여도) | 바로 |
| 계속 실패 중인 케이스만, 같은 스위트·환경에서 간격 안에 실패 알림을 보냄 | 생략 |
| 계속 실패 중인 케이스만, 간격이 지남 | 다시 알림 (`계속 실패 중`, 몇 회째·언제부터) |
| 모두 통과 (직전 실행에 실패) | 바로 복구 알림 |

- 일부만 복구되면 알리지 않고 다음 재알림에 남은 실패만 표시
- 간격은 마지막으로 보낸 실패 알림의 실행 시작 시각부터. 실행 시작이 폴링 주기만큼 늦어져도 제때 다시 알리도록 폴링 주기만큼 여유
- 보낸 실행은 `test_run.failure_notified_at` 기록. 채널 발송 실패도 기록 (다음 재알림까지 생략)
- `0s`이면 실행마다 발송 (안내 줄 없음). 음수면 기동 실패
- 생략하면 INFO 로그 1줄 (`Skipped failure notification for run 12: still failing and notified within PT1H`)

### 응답시간 악화 알림

판정 규칙의 시간 한도를 넘기 전에 응답시간이 나빠진 것을 알림. 매시 5분에 케이스·환경별로 최근 24시간 p95를 그 전 7일 p95와 비교

```
[Sentinel] 결제 - 응답시간 악화 1건
결제 · 결제 승인 (dev) p95 320ms → 610ms (+91%, 최근 응답 144건)
대시보드: https://sentinel.example.com/
```

```
[Sentinel] 결제 - 응답시간 회복 1건
결제 · 결제 승인 (dev) p95 340ms로 회복 (악화 당시 610ms, 기준 320ms)
```

| 판단 | 기준 |
|------|------|
| 악화 | 최근 p95가 비교 p95보다 50% 이상 **그리고** 100ms 이상 느림 (20ms→40ms처럼 빨라서 의미 없는 두 배 제외) |
| 회복 | 감지 당시 비교 p95 대비 25% 미만 **또는** 50ms 미만으로 돌아옴 (악화 기준의 절반. 기준선에서 오르내리며 악화·회복이 번갈아 오지 않도록) |
| 대상 | 두 기간 모두 응답을 받은 결과가 5건 이상 (타임아웃·연결 실패 제외). 수동·스케줄 실행 모두 (대시보드 응답시간 변화와 같은 집계) |

- 느린 동안은 다시 알리지 않음 (`slowdown_alert`에 열린 알림 1건). 회복 판단은 감지 당시 비교 p95 기준이라, 7일이 지나 비교 기간이 느린 값을 흡수해도 회복으로 보지 않음
- 최근 24시간 응답이 5건 미만이면(스케줄 중지 등) 판단하지 않고 열어 둠. 삭제된 케이스·환경의 열린 알림은 알리지 않고 닫음
- 프로젝트별로 묶어 1건씩, 프로젝트 Webhook이 있으면 그 채널 ("프로젝트별 채널"). 늘어난 비율이 큰 순
- 스케줄 폴링(`sentinel.schedule.enabled`)이 꺼져 있으면 함께 꺼짐. 확인 시각에 꺼져 있었으면 다음 시각에 같은 방식으로 다시 확인

`GET /api/slowdowns?suiteId=`: 지금 느린 케이스·환경 (알림 후 아직 회복하지 않음), 감지 당시 늘어난 비율이 큰 순. `suiteId`는 그 스위트만. 삭제된 케이스·환경 제외

```json
[{"id": 3, "testCase": {"id": 12, "name": "결제 승인"}, "suite": {"id": 3, "name": "결제"}, "project": {"id": 1, "name": "결제"}, "environment": {"id": 2, "name": "dev"}, "baselineP95": 320, "detectedP95": 610, "increase": 0.906, "detectedAt": "2026-10-04T02:05:00Z"}]
```

- `baselineP95`·`detectedP95`·`increase`는 감지 당시 값 (지금 값은 대시보드 응답시간 변화·스위트 응답시간에서 확인)

| 설정 | 기본값 | 설명 |
|------|--------|------|
| `sentinel.slowdown.enabled` | `true` | 확인 여부 |
| `sentinel.slowdown.cron` | `0 5 * * * *` | 확인 시각 (Spring cron 6자리). 잘못된 형식이면 기동 실패 |
| `sentinel.slowdown.increase-ratio` | `0.5` | 악화 기준 비율 (0.5 = 1.5배) |
| `sentinel.slowdown.min-increase` | `100ms` | 악화 기준 최소 증가폭 |
| `sentinel.slowdown.recent-window` | `24h` | 최근 기간 |
| `sentinel.slowdown.baseline-window` | `7d` | 비교 기간 (최근 기간 바로 앞) |
| `sentinel.slowdown.min-samples` | `5` | 두 기간 각각 필요한 최소 응답 수 |

### 주간 요약

실패·복구·공백이 있을 때만 오는 알림과 달리, 한 주의 흐름을 대시보드를 열지 않고 확인하도록 매주 1회 기본 채널로 발송

```
[Sentinel] 주간 요약 09-21 ~ 09-27
실행 1,008회 · 통과율 98.6% (지난주 대비 +0.4%p) · 실패·오류가 있는 실행 18회 (지난주 21회)
통과율 낮은 스위트: 결제 98.0%, 정산 99.1%
실패 원인: 서버 오류 5xx 74%, 타임아웃 20%, 응답 내용 불일치 4% 외 1가지
불안정 케이스 5건: 결제 승인 (dev), 환불 조회 (staging), 회원 조회 (dev) 외 2건
응답시간 나빠진 케이스 (p95): 결제 승인 (dev) 320ms → 610ms
모니터링 공백 2건 · 4시간 0분
대시보드: https://sentinel.example.com/
```

- 기간: 발송일 전날까지 7일 (월요일 발송이면 지난주 월~일). 비교는 바로 앞 7일
- 값은 대시보드와 같은 기준 (스위트 요약·실패 원인·불안정 케이스·응답시간 변화·모니터링 공백 API). 실패 원인은 전체 실패·오류 대비 비율로 많은 순 3가지까지, 나머지는 가짓수. 줄마다 3건까지, 나머지는 "외 N건". 해당 항목이 없는 줄은 생략
- 기간 안 실행이 없으면 스케줄이 꺼져 있거나 Sentinel이 멈춰 있었는지 확인하도록 안내
- 대시보드 링크는 `sentinel.notification.web-base-url` 지정 시
- 발송 시각에 꺼져 있었으면(노트북 절전 등) 켜진 뒤 1회 발송. 7일이 지난 예정분은 보내지 않음 (몇 주 꺼져 있었으면 가장 최근 예정분만)
- 설치 전에 끝난 기간의 요약은 보내지 않고 처리한 것으로 기록. 설치일은 첫 DB 마이그레이션 적용일(Flyway 이력, 스케줄 시간대 기준). 새로 설치하자마자 실행이 없던 지난주 요약이 가지 않도록
- 예정 시각마다 발송 기록(`digest_delivery`)을 남겨 재시작해도 다시 보내지 않음. 기록을 먼저 남기므로 발송 실패 시에도 다시 보내지 않음 (같은 요약 반복 방지, 실패는 ERROR 로그)
- 스케줄 폴링(`sentinel.schedule.enabled`)이 꺼져 있으면 함께 꺼짐. 폴링 주기마다 예정 시각이 지났는지 확인

| 설정 | 기본값 | 설명 |
|------|--------|------|
| `sentinel.digest.enabled` | `true` | 발송 여부 |
| `sentinel.digest.cron` | `0 0 9 * * MON` | 발송 시각 (Spring cron 6자리, 초 포함). `sentinel.schedule.zone` 기준. 잘못된 형식이면 기동 실패 |

### 발송 채널

| 설정 | 기본값 | 설명 |
|------|--------|------|
| `sentinel.notification.webhook-url` | 없음 (`SENTINEL_WEBHOOK_URL`) | 수신 Webhook 주소. 미지정 시 WARN 로그로만 기록 |
| `sentinel.notification.web-base-url` | 없음 (`SENTINEL_WEB_URL`) | 결과 화면 주소. 지정 시 실패 알림 마지막 줄에 `결과: {주소}/runs/{id}` 추가 |
| `sentinel.notification.connect-timeout` | `3s` | 연결 제한 시간 |
| `sentinel.notification.read-timeout` | `5s` | 응답 대기 제한 시간 |
| `sentinel.notification.repeat-interval` | `1h` | 계속 실패 중 재알림 간격 (위 "계속 실패 중 재알림"). `0s`이면 실행마다 |

- 본문 서식은 Webhook 주소로 판별 (기본 채널·프로젝트별 채널 공통)

  | 주소 | 서식 |
  |------|------|
  | `hooks.slack.com` | Slack Block Kit: 굵은 제목 머리, 본문, 링크 버튼(`결과 보기`·`대시보드 보기`), 성격별 색 막대 |
  | `*.logic.azure.com`·`*.powerplatform.com`(Teams 워크플로), `*.webhook.office.com`(기존 커넥터) | Teams Adaptive Card: 성격별 제목 색, 줄마다 글 상자, 링크 버튼 |
  | 그 외 (자체 중계 서버 등) | `{"text": "제목\n본문\n결과: 주소"}` 한 필드 (예전과 같음) |

  - 성격: 실패·장애(빨강), 악화·공백(주황), 복구·회복(초록), 요약·확인(회색). Teams는 Attention·Warning·Good·Default
  - 두 서식 모두 알림 미리보기용 `text`(제목)를 함께 보냄. Slack 본문은 서식 문법(`*`, `_`)으로 해석되지 않도록 일반 텍스트로 보내고 블록 글자 수 제한(머리 150자, 본문 3,000자)을 넘으면 자름
  - Teams의 새 워크플로 Webhook("Webhook 요청을 받으면 채널에 게시")은 카드 형식이 필요하여, 예전 텍스트 한 필드로는 게시되지 않을 수 있음
- Webhook 주소는 자격증명이므로 환경변수로 주입하고 로그·git에 남기지 않음 (`sentinel.env`)
- 연결 확인: `POST /api/notification-deliveries` 본문 없이(또는 `{}`) (관리자만). 기본 채널로 "기본 알림 채널 연결 확인" 알림을 실제 알림과 같은 경로로 보내고 그 발송 기록을 201로 (아래 "발송 이력"). 주소가 없으면(로그에만 기록하는 상태) 409
- 발송 실패(4xx·5xx, 타임아웃, 연결 실패)는 WARN 로그만 남기고 실행·폴링은 계속
- 다른 형식이 필요하면 `com.sentinel.application.notification.Notifier` 구현체 추가 후 `NotificationConfig`의 빈 교체

### 프로젝트별 채널

스케줄 실행 실패·복구 알림을 프로젝트마다 다른 Webhook으로 보냄. 지정하지 않은 프로젝트와 스케줄러 알림(스케줄 실행 오류, DB 연결 실패·복구, 모니터링 공백)과 주간 요약은 위 기본 채널

| API | 설명 |
|-----|------|
| `PATCH /api/projects/{id}` `{"webhookUrl": "https://..."}` | 지정. http(s) 절대 주소, 1000자 이하. `""`이면 삭제(기본 채널로). 암호화 키(`sentinel.secret.key`) 미설정이면 400 |
| `POST /api/notification-deliveries` `{"projectId": 3}` | 연결 확인 알림 발송. 201 + 발송 기록 (`delivered`, `statusCode`는 받은 응답, `error`는 응답이 없는 사유 예: `CONNECTION_FAILED`·`BLOCKED_DESTINATION`). Webhook이 없으면 409, 주소를 읽을 수 없으면(키 변경) 400 |

- 주소는 자격증명이므로 환경 시크릿과 같은 키로 암호화해 저장(`project.webhook_url_cipher`, V21). 조회 응답에는 지정 여부(`webhookConfigured`)만, 변경 이력에는 항목 이름(`webhook`)만. 로그·오류 응답에도 주소를 넣지 않음
- 로그인한 사용자 누구나 지정할 수 있는 주소라 실행기와 같은 목적지 확인을 거침: 링크 로컬·클라우드 메타데이터 주소로는 보내지 않고(`BLOCKED_DESTINATION`) 리다이렉트를 따라가지 않음 (서버 기본 채널은 운영자가 환경변수로 지정하므로 기존 방식)
- 주소를 읽을 수 없으면(암호화 키 변경·미설정) 알림을 잃지 않도록 기본 채널로 보내고 WARN 로그. 연결 확인 알림은 기본 채널로 대신 보내지 않음
- 발송 실패는 기본 채널과 같이 WARN 로그만 (프로젝트 ID·알림 제목·상태 코드 또는 사유, 주소 없음)

### 발송 이력

알림을 보낼 때마다 시각·채널·제목·결과를 기록. "알림이 안 왔다"를 서버 로그 없이 확인 (모든 사용자 조회)

| API | 설명 |
|-----|------|
| `GET /api/notification-deliveries?projectId=&failed=&page=&size=` | 발송 시각 최신순 20건 단위(`size` 1~100). `projectId`는 그 프로젝트 Webhook으로 보낸 것만, `failed=true`는 보내지 못한 것만 |
| `GET /api/notification-deliveries/{id}` | 단건 (연결 확인 알림의 `Location`) |
| `POST /api/notification-deliveries` | 연결 확인 알림 발송 (만들 수 있는 발송은 연결 확인뿐). 본문 `{"projectId": 3}`은 그 프로젝트 Webhook(모든 사용자), 생략하면 서버 기본 채널(관리자만). 실제 알림과 같은 경로로 보내고 201 + `Location` + 발송 기록. 보내지 못해도 기록은 남으므로 결과는 `delivered`·`statusCode`·`error` |
| `GET /api/notification-channels` | 알림 채널: 서버 기본 채널(첫 항목)과 Webhook을 지정한 프로젝트(이름 순) |

- 항목: `{id, sentAt, channel, project: {id, name}, format, title, tone, delivered, statusCode, error}`
  - `channel`: `DEFAULT`(서버 기본 채널, `project`는 `null`) / `PROJECT`(프로젝트 Webhook). 삭제된 프로젝트도 이름 표시
  - `format`: `SLACK` / `TEAMS` / `TEXT` / `LOG`(기본 채널 주소 미설정, 서버 로그에만 기록)
  - `tone`: `FAILURE`(실패·장애) / `WARNING`(악화·공백) / `SUCCESS`(복구·회복) / `INFO`(요약·확인)
  - `delivered`: 2xx 응답을 받음 (`LOG`는 `true`). `statusCode`는 받은 응답, `error`는 응답을 받지 못한 사유 (`TIMEOUT`·`CONNECTION_FAILED`·`BLOCKED_DESTINATION` 등)
- 대상: 기본 채널·프로젝트 채널로 보낸 모든 알림 (실패·복구, 재알림, 스케줄 실행 오류, DB 연결 실패·복구, 모니터링 공백, 주간 요약, 응답시간 악화·회복, 연결 확인)
- Webhook 주소(자격증명)와 본문은 기록하지 않음. 제목은 300자까지
- 발송 뒤 별도 트랜잭션으로 기록. 기록 실패(DB 연결 끊김 등)는 WARN 로그만 남기고 알림에는 영향 없음 (DB 연결 실패 알림은 기록되지 않을 수 있음)
- 보관기간(`sentinel.retention.notification-retention`, 기본 90일)이 지나면 매일 정리 배치에서 삭제
- 알림 채널 항목: `{channel, project: {id, name}, configured, format, failing}`
  - `configured`: 주소가 있음. `false`는 기본 채널뿐 (`sentinel.notification.webhook-url` 미설정, 알림이 서버 로그에만 남음)
  - `format`: 주소로 판별한 서식. 기본 채널 주소가 없으면 `LOG`, 프로젝트 주소를 읽을 수 없으면(키 변경) `null`
  - `failing`: 최근 7일 안 마지막 발송(로그 기록 제외)이 실패면 `{since(이어진 실패 중 가장 이른 시각), failures(마지막 성공 뒤 실패 건수), lastSentAt, statusCode, error}`, 아니면 `null`. 한 번 보내면 `null`
  - Webhook을 지운 프로젝트·삭제된 프로젝트는 채널이 아니므로 목록에 없음. Webhook 주소를 고친 뒤에는 다음 발송(또는 연결 확인 알림)이 성공해야 `failing`이 사라짐

```
Failed to record notification delivery: [Sentinel] DB 연결 실패 (CannotCreateTransactionException)
Deleted 12 notification delivery record(s) sent before 2026-07-07T18:30:00Z
```

## 패키지 구조 (계획)

```
com.sentinel
 ├─ domain/          엔티티, 도메인 서비스
 ├─ runner/          RequestExecutor, VariableResolver (Spring 의존성 없는 순수 자바)
 │   └─ assertion/   AssertionEvaluator + 구현체
 ├─ application/     TestRunService, SuiteService
 ├─ api/             Controller, 예외 응답 (ApiExceptionHandler)
 │   ├─ request/     요청 본문 (Create·Update…Request). 입력 검증 어노테이션 포함
 │   └─ response/    컨트롤러에서만 만드는 응답 (세션·링크 발급 등). 조회 결과는 application의 타입을 그대로 응답
 └─ infra/           Repository, 스케줄러, 암호화
```

접근 제한
- 다른 패키지의 운영 코드가 쓰지 않는 타입은 package-private (컴포넌트·설정 Properties·내부 변환기 등). Spring 빈·설정 바인딩·트랜잭션 프록시는 package-private 클래스에도 동작
- 예외로 public 유지: 실행 진입점(`SentinelApplication`), 리포지토리가 상속하는 조회 조각(`XxxQueries`), public 응답 레코드의 필드 타입(`IdName`), 다른 패키지 테스트가 고정 데이터로 만드는 판정 규칙 하위 타입(JSON 다형성)
- 메서드·생성자도 같은 규칙: public 클래스라도 다른 패키지의 운영 코드가 부르지 않으면 package-private. 인터페이스 구현(`@Override`), Spring·Jackson이 부르는 메서드(`@Bean`·`@JsonCreator` 등), 레코드 생성자(레코드보다 좁힐 수 없음), Lombok이 만드는 접근자는 제외
  - 예외로 public 유지: 판정 결과 값 `AssertionResult.pass/fail` (다른 패키지 테스트가 고정 데이터로 만듦, 판정 규칙 하위 타입과 같은 이유)
- 테스트는 대상과 같은 패키지에 둠 (package-private 타입을 다른 패키지 테스트를 위해 public으로 열지 않음). 다른 패키지 테스트의 데이터 준비는 같은 패키지 도우미 경유 (`TestFixtures.runSuite`, `TestAuthConfiguration.passwordHash`)

설계 배경 및 유의사항은 [docs/design.md](../docs/design.md) 참고

## Spring Boot 4 유의사항

- autoconfigure의 목적별 소형 모듈 분리. 3.x 예제 의존성 복사 시 실패하므로 [start.spring.io](https://start.spring.io)에서 확인
- Jackson 3.x 사용, 패키지 경로 `tools.jackson`으로 변경
