# API 검증 자동화 테스트 에이전트 설계 정리

---

## 1. 과제 정의

### 목적
손으로 반복하던 API 검증을 특정 시간에 자동 실행하고, 결과 이력을 공유하는 도구 구축

### 성격
- "에이전트"는 LLM 기반이 아닌, 정해진 케이스를 실행하는 자동화 Runner

### 구축 근거 (기존 도구와의 차이)
- Postman Monitors: 스케줄 기능은 존재하나 Postman 클라우드에서 실행되어 내부망 사용하는 GW 접근 불가  
  - 요청 수 기준 과금이며, 2026년 요금제에서 무료 플랜에서 스케줄 모니터 제외
- Bruno: 오프라인 전용으로 스케줄 기능 자체가 없음.
  - CLI를 CI 파이프라인에 태우는 방식이라 별도 CI 구축 필요, 결과가 콘솔 로그로만 남아 공유에 부적합

---

## 2. 아키텍처

### 전체 구조
```
[브라우저] → Next.js (:3000, BFF) → Spring Boot (:8080) → GW → 내부 모듈 N개
                                          ↓
                                  PostgreSQL(sentinel 스키마)
```

### Repo 구성
단일 Repo 내 디렉터리 분리

```
sentinel/
 ├─ api/          Spring Boot
 ├─ web/          Next.js
 ├─ docker-compose.yml
 └─ README.md
```

커밋 하나로 API 변경과 화면 변경 동시 반영, 타입 불일치가 구조적으로 발생하지 않음.  
추후 분리 필요 시 git filter-repo로 분할 가능

### 구간별 인증 분리
- 브라우저 → Next.js: 세션 쿠키. Next가 쿠키 관리, 브라우저에는 Next 도메인 쿠키만 노출
- Next.js → Spring: 서버사이드 호출, CORS 미발생. Server Component는 Spring 직접 호출(`lib/api/server.ts`), 브라우저 요청은 Route Handler 프록시(`/api/**`) 경유 (Next.js 16 권장 방식, 2026-09-19 구현). 등록·수정·삭제·실행은 Server Actions에서 Spring 호출 후 `refresh()`·`redirect`로 화면 갱신 (2026-09-20)
- Spring → GW: 환경별로 저장된 시스템 자격증명을 Spring이 주입. 사용자 토큰 패스스루 금지

### REST API 설계 (2026-09-19)
- 모든 리소스는 최상위 컬렉션: `/api/projects`, `/api/environments`, `/api/suites`, `/api/cases`, `/api/runs`, `/api/schedules`
- 관계는 중첩 경로 대신 쿼리 파라미터 필터 (`/api/suites?projectId=1`). 실행·스케줄은 부모가 둘(스위트·환경)이고 실행 이력은 필터 조건이 늘어날 예정이므로 중첩보다 일관성 유지에 유리
- 존재하지 않는 부모 ID로 필터링하면 빈 목록(200). 404는 단건 조회(`/{id}`)만
- URL에 동사 미사용. 상태 변경은 `PATCH` 부분 수정 (`PATCH /api/schedules/{id}` `{"enabled": false}`)
- 실행 생성 입구는 `POST /api/runs` 하나. 대상은 본문의 `suiteId` 또는 `caseId` 중 정확히 하나
- 생성은 201 + `Location`, 오류는 ProblemDetail
- 수정은 `PATCH` 부분 수정. 생략한 필드는 유지, 목록·객체 값(환경 변수, 요청 정의, 판정 규칙)은 지정 시 전체 교체
- 상태 코드: 잘못된 값 400, 미존재·보관 404, 사용 중인 이름 중복 409 (사전 검사 + 동시 등록 경합은 DB 유니크 제약으로 차단)

### 인증 구현 (2026-09-20)
- 세션 방식: Spring이 불투명 토큰 발급(무작위 32바이트) → DB에 SHA-256 해시로 저장, Next.js가 httpOnly 쿠키로 보관
  - JWT 미사용: 로그아웃·사용자 삭제 시 즉시 무효화가 필요하고, 서버가 하나라 세션 조회 비용이 문제되지 않음
  - 만료는 로그인 시점 기준 고정(기본 12h). 사용 시 연장하지 않음
- 비밀번호는 BCrypt 해시. 로그인 실패는 사유를 구분하지 않고 응답 시간도 비슷하게 유지(계정 존재 여부 비노출)
- Spring Security 스타터 미사용: 자동 설정이 전체 요청을 가로채 기존 구조와 충돌. BCrypt만 가져와 필터 직접 구현
- 최초 관리자 계정은 환경변수로 1회 생성
- 가입은 관리자 초대 링크만 (2026-09-20). 공개 가입·메일 기반 비밀번호 찾기 미사용
  - 메일 발송 수단이 없고, 시크릿을 보관하는 도구라 가입 범위를 관리자가 통제
  - 초대·재설정 토큰은 1회용, 해시 저장, 만료(초대 7일·재설정 2시간). 만료·사용·위조 모두 404로 동일 응답
  - 비밀번호 변경·재설정 시 해당 사용자 세션 전체 종료
  - 링크 전달은 사람이 직접 수행. SMTP가 준비되면 메일 발송만 추가하면 됨
  - 화면(2026-09-20): `/users`(목록·초대·재설정·삭제), `/settings/password`, `/invites/{token}`, `/password-resets/{token}`
  - 링크 주소는 요청 헤더의 호스트로 구성하여 배포 주소를 따로 설정하지 않음

### 인증 범위
- 회원가입, 비밀번호 찾기, 이메일 인증, 약관 동의 전부 불필요
- SSO 존재 시 SSO만 연동, 없으면 관리자 직접 등록
- 역할 구분 초기 미도입, 필요 시 ADMIN 추가
- 인증을 남기는 이유는 접근 통제가 아니라 저장된 운영 토큰 보호 및 실행 주체 기록
- 실행 주체 기록 (2026-09-22): 수동 실행은 `test_run.triggered_by`에 사용자 ID 보관, 스케줄 실행은 null
  - 이름은 조회 시점에 확인하여 사용자 삭제(보관) 후에도 이력 유지
  - 목록은 페이지당 1회로 일괄 조회

### 보안 유의사항
- 임의 URL 실행이 가능한 구조이므로 SSRF 위험 존재.  
  - Spring Boot 4.1의 HTTP 클라이언트 SSRF 완화 기능 우선 검토 후 부족분만 화이트리스트로 보완
- 환경 단위 허용 호스트 화이트리스트. GW가 하나이므로 호스트 1개 등록으로 해결
- 환경별 토큰·API 키는 시크릿으로 분리하여 애플리케이션 레벨 AES-GCM 암호화 저장 (2026-09-20 구현)
  - pgcrypto 미사용: 표준 SQL 유지, DB 접근만으로 복호화 불가하도록 키는 애플리케이션이 보관(`SENTINEL_SECRET_KEY`)
  - 조회 API·화면은 이름만 노출, 실행 이력 저장 시 값 마스킹. 키 미설정 시 등록 자체를 거부하여 평문 저장 방지
  - 키 교체 시 기존 값 복호화 불가(재입력 필요). 자동 재암호화는 미구현
- Spring 주소는 서버 전용 환경변수로 관리. NEXT_PUBLIC_ 접두어 사용 시 브라우저 번들에 내부 주소 노출

---

## 3. 기술 스택

| 구분 | 선택 |
|------|------|
| Java | 25 |
| BE | Spring Boot 4.1 (Spring Framework 7.0.8 기반) |
| ORM | JPA (heavy query 부재, 단순 스키마) |
| DB | PostgreSQL — 개발기 AWS RDS의 sentinel 스키마 |
| 실행기 | RestClient + Apache HttpClient 5 (2026-09-26 JDK HttpClient에서 전환, DNS rebinding 방어) |
| 스케줄 | DB 폴링 (5장 참조) |
| 스키마 관리 | Flyway + ddl-auto: validate (2026-09-19 배포 구성 시 전환) |
| 코드 생성 | Lombok `@Getter`, `@RequiredArgsConstructor`, `@NoArgsConstructor(access = PROTECTED)`만 사용 (2026-09-19, 아래 "Lombok 사용 규칙") |
| FE | Next.js 16.3.5 (Active LTS), App Router, TanStack Query, shadcn/ui (Radix UI) |
| FE 디자인 | Sentry 제품 화면 참고 (짙은 보라 사이드바 + 흰 본문, 주 버튼 화면당 1개, 부수 동작은 "⋯" 메뉴), Pretendard. 규칙은 `web/DESIGN.md` (2026-09-26) |
| 실행 환경 | 노트북 상시 실행, Docker 컨테이너 2개 |

### Spring Boot 4 관련 유의사항
- spring-boot-autoconfigure가 목적별 소형 모듈로 분리됨. 기존 3.x 예제의 의존성을 그대로 복사하면 실패. start.spring.io에서 생성 권장
- Jackson 3.x로 변경, 패키지 경로가 tools.jackson으로 이동
- @Retryable이 프레임워크에 포함 (재시도 횟수·지연·지터·백오프 설정 가능)
- @ConcurrencyLimit으로 동시 호출 수 제한 가능. 스위트 실행 시 GW 동시 호출 제어에 활용
- @Async 비동기 컨텍스트 전파 지원. 스케줄 실행 로그 추적에 유리
- 스케줄러는 DB 폴링으로 확정하여 Quartz 스타터 미사용

### Lombok 사용 규칙 (2026-09-19)
- 허용 기능은 세 가지. 그 외 기능은 `api/lombok.config`에서 사용 시 컴파일 오류로 차단
  - `@Getter`: 엔티티·임베더블 전용. 서비스·컨트롤러에는 미사용 (필드 노출 불필요)
  - `@RequiredArgsConstructor`: 필드 대입만 하는 생성자 대체. 생성자에 검증·조립 로직이 있으면 직접 작성
    - 프로그래밍 방식 트랜잭션은 `Transactions` 빈(`write`, `writeWithoutResult`, `readOnly`)으로 모아 사용하는 쪽 생성자를 대입만으로 유지
    - 직접 작성 대상: 검증하는 도메인 생성자, 예외, 입력을 복사·조립하는 생성자(`Transactions`, `VariableResolver`, `RestClientRequestExecutor`)
  - `@NoArgsConstructor(access = AccessLevel.PROTECTED)`: JPA 기본 생성자 전용. public이면 검증 없는 빈 엔티티 생성이 가능하므로 PROTECTED 필수
    - 접근 수준은 lombok.config로 강제할 수 없어 `JpaDefaultConstructorTest`로 모든 엔티티·임베더블 검사
    - 유틸리티 클래스의 private 생성자는 직접 작성 유지 (`@UtilityClass` 차단)
- 차단 기능과 이유
  - `@Setter`, `@Data`: 상태 변경은 검증을 포함한 도메인 메서드(`rename`, `changeBaseUrl`, `archive` 등)로만 수행
  - `@Builder`, `@AllArgsConstructor`: 생성자 검증 우회
  - `@EqualsAndHashCode`, `@ToString`: 지연 로딩 프록시와 충돌. 동일성 비교는 `BaseEntity.same()`
  - `@Value`: DTO·값 객체는 record 사용
- 방어적 getter는 직접 작성 유지. 같은 이름의 메서드가 있으면 Lombok은 생성하지 않음
  - 대상: `Environment.getVariables()`, `RequestSpec.getHeaders()`, `TestCase.getAssertions()`, `TestResult.getAssertionResults()`
  - 컬렉션 필드를 수정할 수 없는 형태로 반환. Lombok getter로 바뀌면 내부 컬렉션이 노출되어 도메인 메서드 검증 우회, 변경 감지로 DB 반영
  - 삭제해도 컴파일 오류가 나지 않으므로 반환값 수정 시 예외 발생을 테스트로 고정 (DB에서 읽은 JSON 목록은 가변 목록이므로 저장 후 재조회로 검증)
- 노출이 불필요한 필드는 `@Getter(AccessLevel.NONE)` (`ArchivableEntity.archiveKey`)
- 버전은 Spring Boot 의존성 관리 버전 사용. JDK 25는 Lombok 1.18.40 이상 필요

### Next.js 버전 근거
- 16은 Active LTS, 보안 지원 종료 2027년 10월 예상
- 15는 Maintenance LTS로 2026년 10월 종료 예정이라 신규 채택 부적합
- 8월 보안 릴리스에서 Critical 2건 수정. 16.3.3 이상 필수
- 16.2.12부터 TypeScript 7 지원

### 초기 설정
```yaml
spring:
  datasource:
    url: jdbc:postgresql://<rds-endpoint>:5432/<db>?currentSchema=sentinel
    hikari:
      maximum-pool-size: 5
      connection-timeout: 10000
      keepalive-time: 300000
  jpa:
    hibernate:
      ddl-auto: validate   # 2026-09-19 update에서 전환. 스키마 변경은 Flyway 마이그레이션으로만 수행
    properties:
      hibernate.default_schema: sentinel
      hibernate.format_sql: true
```

```js
// next.config.js
module.exports = { output: 'standalone' };
```

---

## 4. 도메인 모델

```
Project ─ Environment (baseUrl, 변수셋, 시크릿)
        ─ TestSuite ─ TestCase ─ RequestSpec
                              ─ Assertion[]
                              ─ Extractor[]
        ─ TestRun ─ TestResult
        ─ TestSchedule (suite_id, cron, environment_id, next_run_at, enabled)
```

### 환경 모델링 원칙
- GW가 하나이므로 Environment는 GW 단위로 하나, 모듈은 경로 프리픽스로 구분
- 모듈별 환경 분리 금지. 개발/스테이징/운영 전환이 baseUrl 치환 한 번으로 끝나야 함
- 모듈 단위 실행 요구는 TestSuite(묶음)와 tags(교차 조회)로 해결

### Assertion 저장
- test_case.assertions JSON 단일 컬럼 확정 (2026-09-19). 규칙 종류 추가 시 스키마 변경 불필요, 케이스 조회 시 함께 로딩
- `type` 속성으로 종류 구분하는 다형 JSON (statusCode, responseTime, header, jsonPath, bodyContains)
- 규칙 미지정 시 기본 판정 `status in [2xx]`. 미적용 시 5xx 응답도 통과 처리되는 문제 방지
- 결과는 test_result.verdict(PASSED/FAILED/ERROR) + assertion_results JSON(규칙별 설명·통과 여부·실제 값)
- 판정 단위 SQL 조회(예: 응답 시간 규칙 보유 케이스 검색)는 불편. 현재 요구사항에 없음

### 엔티티 유의사항
- TestRun에 TestResult 양방향 @OneToMany 금지. 단방향 @ManyToOne만 두고 리포지토리 조회
- 외래키 제약 미사용 (2026-09-19). 참조 무결성은 애플리케이션에서 보장
  - 엔티티 `@JoinColumn(foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))`로 DDL 생성 시에도 FK 미생성
  - 조회용 인덱스와 유니크 제약은 유지
  - SQL 직접 입력 시 ID 하드코딩 대신 유니크한 이름으로 부모 ID 조회하는 스칼라 서브쿼리 사용. 이름 오타 시 NOT NULL 위반으로 실패하여 잘못된 참조 차단 (`docs/sample-cases.sql` 방식)
  - 삭제 시 하위 데이터 처리는 애플리케이션 책임 (아래 "삭제 정책")

### 삭제 정책 (2026-09-19)
소프트 삭제(보관) 확정. 실행 이력이 핵심 자산이므로 행을 지우지 않고 `archived_at` 기록

- 대상: 프로젝트, 환경, 스위트, 케이스, 스케줄. 실행(TestRun)·결과(TestResult)는 삭제 대상 아님
- 연쇄 보관 (`CatalogCommandService`)
  - 프로젝트 → 환경, 스위트(케이스·스케줄 포함)
  - 환경 → 이 환경을 쓰는 스케줄
  - 스위트 → 케이스, 스케줄
  - 스케줄은 보관 시 비활성화하여 폴링 대상에서 제외
- 보관된 항목은 목록에서 제외, 단건 조회·재삭제·실행은 404. 실행 이력 화면은 보관된 케이스 이름까지 그대로 표시
- 이름 유니크 제약과의 충돌: `archive_key` 컬럼(사용 중 0, 보관 시 자기 ID)을 유니크 제약에 포함. 사용 중인 행끼리의 중복은 DB가 차단, 보관된 이름은 재사용 가능 (표준 SQL, 부분 인덱스 불필요)
- 조회 쿼리는 `archived_at IS NULL` 조건 명시 (리포지토리 메서드명 `...ArchivedAtIsNull`). Hibernate `@SQLRestriction`은 이력의 케이스 참조까지 걸러지므로 미사용
- responseBody는 저장 전 크기 제한 (예: 64KB) 후 truncate
- 인덱스는 데이터 축적 전 선반영

### SQL 작성 원칙
ANSI SQL 표준 준수 (2026-09-19). 마이그레이션, 샘플, 운영 쿼리 등 직접 작성하는 모든 SQL 대상

- DB 전용 문법 미사용: `RETURNING`, `::` 캐스트, `now()`, `ON CONFLICT`, `IF EXISTS` 등
- 표준 타입만 사용
  - JSON 값: `JSON` (SQL:2016 표준 타입). 환경 변수, 요청 헤더, 판정 규칙·결과 저장
  - 대용량 문자열: `VARCHAR(n)` 길이 명시. 요청·응답 본문 `VARCHAR(65536)`. 표준 `CLOB`은 PostgreSQL 미지원으로 미사용
  - 식별자: `BIGINT GENERATED BY DEFAULT AS IDENTITY`, 시각: `TIMESTAMP(6) WITH TIME ZONE`
- 표준 예약어를 컬럼명으로 미사용: `TRIGGER` → `trigger_type`, `METHOD` → `http_method`
- 부모 ID는 하드코딩 대신 유니크한 이름으로 조회하는 스칼라 서브쿼리, 현재 시각은 `CURRENT_TIMESTAMP`
- 표준 정의가 없어 예외로 허용
  - `CREATE INDEX`: 표준 미정의, 주요 DB 공통 구문
  - 계정·권한 관리(`CREATE USER`, 스키마 `GRANT`): DB 관리 명령
  - `DatabaseTimeoutTest`의 `pg_sleep`: PostgreSQL 드라이버 타임아웃 검증이 목적
- 표준 집계·시간대 문법 사용: 백분위 `PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY ...)`, 날짜 변환 `CAST(... AT TIME ZONE ... AS DATE)`
- 네이티브 쿼리의 테이블은 `{h-schema}`(Hibernate 기본 스키마 치환)로 한정. 네이티브 쿼리에는 `hibernate.default_schema`가 적용되지 않아 JDBC URL의 `currentSchema`에 의존하지 않도록 함

```sql
CREATE SCHEMA sentinel;
CREATE USER sentinel_app WITH PASSWORD '...';
GRANT USAGE, CREATE ON SCHEMA sentinel TO sentinel_app;

CREATE INDEX idx_result_run       ON test_result(run_id);
CREATE INDEX idx_result_case_time ON test_result(test_case_id, id DESC);
CREATE INDEX idx_run_suite_time   ON test_run(suite_id, started_at DESC);
```

공용 RDS이므로 스키마 한정 권한 계정 사용. Flyway가 sentinel 스키마 안에 테이블과 이력 테이블(flyway_schema_history)을 생성하므로 스키마 내 CREATE 권한 필요

---

## 5. 러너 및 스케줄 설계

### 패키지 구조
```
com.xxx.apitest
 ├─ domain/          엔티티, 도메인 서비스
 ├─ runner/          RequestExecutor, VariableResolver
 │   └─ assertion/   AssertionEvaluator + 구현체
 ├─ application/     TestRunService, SuiteService
 ├─ api/             Controller, DTO
 └─ infra/           Repository, 스케줄러, 암호화
```

runner 패키지는 Spring 의존성 없는 순수 자바로 유지. 제품의 핵심이자 단위 테스트 집중 대상

### 테스트 전략
- 순수 UT 대상: VariableResolver(변수 치환, 미해결 변수, 이스케이프), AssertionEvaluator 구현체(경계값, null, 타입 불일치), Extractor
- 통합 테스트 대상: RequestExecutor. Testcontainers WireMock 컨테이너로 타임아웃·리다이렉트·4xx/5xx 검증
- DB 의존 테스트는 Testcontainers PostgreSQL 컨테이너 사용. RDS 접속 없이 빌드·테스트 가능
- RequestExecutor를 인터페이스로 분리하면 TestRunService 테스트에서 Fake 주입으로 네트워크 없이 흐름 검증 가능
- 커버리지 불필요 영역: api 컨트롤러, infra 리포지토리
- web: Vitest + MSW(Spring API 대체) + React Testing Library (2026-09-20)
  - 서버 코드(Server Actions, Spring 호출, BFF 프록시): Node 환경에서 MSW로 Spring 응답 지정, 요청 본문·오류 변환·이동 경로 검증
  - 클라이언트 컴포넌트: jsdom 환경, Server Action은 테스트용 함수로 대체
  - async Server Component 화면은 Vitest 미지원(Next.js 문서)으로 제외. 화면 단위 검증은 추후 E2E(Playwright) 검토
- E2E: Playwright. PostgreSQL·api·web을 모두 띄운 실제 스택(`docker-compose.e2e.yml`) 대상으로 등록 → 실행 → 스케줄 → 삭제 흐름 검증 (2026-09-20)
  - 케이스가 api 자신을 호출하도록 구성하여 외부 의존 없이 실행 결과까지 확인
  - 개발/운영 스택과 포트·데이터 분리 (web 13000, api 18080, DB는 컨테이너 내부 전용·매 실행 폐기)
  - async Server Component 화면은 단위 테스트 대상이 아니므로 이 경로로 검증
- CI: GitHub Actions에서 PR·main 푸시마다 web(lint, 서식, 테스트, 빌드), api(Testcontainers 포함 테스트), E2E 병렬 실행

```java
public interface RequestExecutor {
    HttpExchange execute(ResolvedRequest request);
}
```

### 스케줄 실행 위치
실행 주체는 Spring. 브라우저와 BFF는 등록과 조회만 담당

```
[브라우저] 크론 설정 UI → Next.js BFF → Spring: TestSchedule 저장
                                          → Spring 스케줄러가 읽어 실행
```

BFF나 브라우저에서 실행을 트리거하지 않는 이유
- 브라우저: 탭을 닫으면 종료, 비활성 탭에서 타이머 지연, GW 직접 호출 불가
- BFF(Node): 스케줄이 메모리에만 존재해 재시작 시 소실, misfire 복구 개념 없음, 스케줄 설정과 실행 이력이 분리되어 관리 지점 이원화

### 스케줄러 구현
DB 폴링 방식 확정 (2026-09-18). next_run_at 컬럼 하나로 재시작 복구 처리, misfire 동작 직접 제어. Quartz는 JDBC JobStore 테이블·스키마 접두어 관리 부담 대비 이점 부족으로 미채택

- `SchedulePoller`: 60초 간격(`sentinel.schedule.poll-interval`)으로 `enabled = true AND next_run_at <= now` 조회
- 실행 전 next_run_at 선갱신. 실행 중 장애 시 동일 스케줄 반복 실행 방지
- misfire: 놓친 실행은 1회만 수행, 다음 시각은 현재 시각 기준으로 계산 (Quartz FireAndProceed와 동일 동작)
- cron은 5자리 표준 형식(`분 시 일 월 요일`). FE의 react-js-cron과 형식 일치. 내부적으로 Spring CronExpression에 0초 필드를 붙여 해석
- cron 해석 시간대는 `sentinel.schedule.zone`(기본 Asia/Seoul). 컨테이너 TZ와 무관하게 동작
- 스위트 내 케이스는 순차 실행하여 GW 동시 호출 없음
- 기동 시 RUNNING으로 남은 실행은 ABORTED 처리 (`StaleRunRecovery`). 단일 인스턴스 전제

### 실행 시각 설계
노트북 상시 실행 전제이므로 특정 시간대 기준으로 배치

- 09:10 - 밤사이 배포 결과 확인
- 12:30
- 17:30

새벽 시각은 노트북 미가동 가능성이 높아 부적합.  
스위트 간 실행 시각 분산 필요 (동시 실행 시 GW 부하 오해 소지). 개발환경 야간 중단 여부 사전 확인

### 연결 실패 처리
IP 변경으로 RDS 접속이 끊기면 스케줄이 실행되지 않는 상태 발생

- 스케줄 진입 시 커넥션 획득 실패는 케이스 실패가 아닌 별도 이벤트로 처리. DB 불가 상태이므로 DB 기록 없이 알림 발송
- 연결 실패 알림은 최초 주기 1회, 복구 시 1회만 발송(중단 기간 포함). 폴링 주기마다 반복 발송 금지
- 재시도 금지. VPN 끊김은 즉시 재시도해도 복구되지 않으므로 스킵 후 다음 주기 대기
- RDS 파라미터 그룹의 statement_timeout, idle_in_transaction_session_timeout 확인 필요
- 클라이언트 측 타임아웃 적용 (2026-09-19): PostgreSQL JDBC `socketTimeout` 30초, `connectTimeout` 10초, `tcpKeepAlive` 사용. 드라이버 기본값 socketTimeout 0(무제한)은 쿼리 도중 네트워크 단절 시 무기한 대기하여 폴링 스레드 정지 유발

### 알림
- 실패 시에만 발송. 성공 알림은 곧 무시됨
- 사실상 이 도구의 주 인터페이스, 화면은 확인용

```
[Sentinel] 개발환경 결제 스위트 - 12건 중 2건 실패
POST /billing/v1/charge  → 500
GET  /billing/v1/history → 응답시간 3200ms (임계 2000ms)
```

구현 (2026-09-19)
- `Notifier` 인터페이스 분리. 기본 구현은 `LoggingNotifier`(WARN 로그)
- Webhook 발송 추가 (2026-09-20): `sentinel.notification.webhook-url` 지정 시 `WebhookNotifier` 사용
  - 본문은 `{"text": "..."}` 한 필드로 Slack·Teams 수신 Webhook 그대로 사용. 다른 형식은 구현체 추가로 대응
  - 주소는 자격증명이므로 환경변수 주입, 로그 미기록
  - 타임아웃은 짧게(연결 3s, 응답 5s) 설정하여 알림이 실행·폴링을 지연시키지 않도록 함
  - `sentinel.notification.web-base-url` 지정 시 실패 알림에 결과 화면 링크(`결과: {주소}/runs/{id}`) 추가. 알림에서 바로 원인 확인 가능
- 발송 대상: 스케줄 실행 결과의 판정 실패·오류, 스케줄 실행 자체 오류, DB 연결 실패·복구. 수동 실행은 화면·API 응답으로 확인하므로 미발송
- 실패 사유는 실패한 판정 규칙만 표시(`status in [2xx]: was 500`), 최대 20줄 후 `… 외 N건`
- 알림 발송 실패는 로그만 기록하고 폴링 계속
- 연속 실패·복구 알림 (2026-09-24)
  - 매번 같은 모양의 실패 알림은 새 장애와 이어지는 장애를 구분할 수 없고, 해결 여부는 화면을 열어야 알 수 있었음
  - 케이스마다 `[새로 실패]`·`[N회째 · 시작 시각부터]` 표시, 제목에 `(새로 실패 K건)`·`(계속 실패 중)`. 새로 실패한 케이스를 앞에 배치
  - 직전 스케줄 실행에 실패가 있었고 이번에 모두 통과하면 복구 알림 1회 (다시 통과한 케이스, 실패 지속 횟수·기간)
  - 비교 대상은 같은 스위트·환경의 이전 스케줄 실행(완료만, 최대 20회). 수동 실행은 수정 확인용인 경우가 많아 제외 (포함하면 복구 알림 누락 가능)
  - 반복 실패 알림 억제(같은 실패는 N회마다 1번 등)는 미적용. 실제 알림 빈도를 본 뒤 검토

---

### 보안 점검 (2026-09-24)

인증·세션·권한과 공격면 점검 결과

| 항목 | 결과 | 조치 |
|------|------|------|
| 로그인 후 이동 주소(`from`) | `/\evil.example`을 브라우저가 `//evil.example`로 해석해 다른 사이트로 이동 (확인) | URL로 해석해 같은 출처 경로만 허용 |
| 실행기 목적지 | 클라우드 메타데이터(169.254.169.254) 접근 가능. 클라우드 배포 시 인스턴스 자격증명 노출 경로 | 링크 로컬·메타데이터 주소 전송 거부 (`BLOCKED_DESTINATION`). 2026-09-26 확인한 주소로만 연결하도록 보완 (DNS rebinding 차단) |
| 로그인 반복 실패 | 제한 없음 (비밀번호 대입 가능) | 이메일별 15분 5회 실패 시 15분 잠금 (429) |
| BFF 프록시 | 사용처가 없는데 쓰기 메서드까지 쿠키 인증으로 전달, 출처 확인 없음 | 조회 전용으로 제한 |
| 인증 필터 경로 판단 | 우회 없음. 다만 라우팅이 점 세그먼트를 정리하지 않는 동작에 의존 | 모호한 경로는 링크 API 예외에서 제외, 실제 Tomcat 대상 회귀 테스트 |
| 세션·링크 토큰 | 32바이트 난수, SHA-256 해시 저장, 만료·1회용 | 유지 |
| 세션 쿠키 | `HttpOnly; Secure; SameSite=Lax`, 스크립트로 읽을 수 없음 | 유지 |
| 로그인 응답 | 없는 계정도 같은 문구·같은 시간(더미 해시 비교) | 유지 |
| XSS | `dangerouslySetInnerHTML` 미사용, 응답 본문은 텍스트로 표시 | 유지 |
| CSP | 미적용 (XSS 발생 시 2차 방어 없음) | 요청별 nonce + `'strict-dynamic'` 정책 적용 (2026-09-25, `web/README.md` 콘텐츠 보안 정책) |
| 시크릿 | AES-GCM 저장, 이름만 노출, 이력은 마스킹 | 유지 |

후속 검토
- 로그인 잠금은 메모리 보관. 여러 인스턴스로 늘리면 공유 저장소 필요

## 6. 실행 환경

### 컨테이너 구성
```
sentinel-proxy :443    Caddy (HTTPS 앞단, 80은 443으로 이동)
sentinel-web   :3000   Next.js (standalone, 127.0.0.1에만 공개)
sentinel-api   :8080   Spring Boot (127.0.0.1에만 공개)
```

루트 `docker-compose.yml`로 실행 (2026-09-19 구성, api와 web 컨테이너 2개)

HTTPS 앞단 (2026-09-24)
- Caddy 선택: 인증서 자동 발급·갱신, HTTP→HTTPS 이동이 기본 동작이라 설정 파일이 짧음. nginx는 인증서 발급·갱신을 별도로 구성해야 함
- 인증서는 `SENTINEL_TLS`로 선택: 사내 주소는 내부 인증 기관(루트 인증서 PC별 신뢰 등록), 공개 도메인은 Let's Encrypt, 보유 인증서 파일도 가능
- web·api는 `127.0.0.1`에만 공개하여 네트워크에서는 HTTPS 앞단만 접근. 평문 HTTP로 비밀번호·토큰이 오가는 경로 차단
- Host 헤더를 그대로 전달하여 Next.js Server Action의 출처 확인(Origin과 Host 비교)이 추가 설정 없이 동작
- 보안 헤더: `X-Content-Type-Options: nosniff`, `Referrer-Policy: strict-origin-when-cross-origin`, `X-Frame-Options: DENY`, `Server` 제거
- HSTS (2026-09-26): 회사 인증서로 결정되어 적용. `SENTINEL_HSTS_MAX_AGE`로 켜며 기본 0(미사용)
  - 내부 인증서와 함께 쓰면 신뢰 등록하지 않은 PC가 경고를 넘길 수 없으므로 신뢰된 인증서일 때만 사용
  - localhost 제외: HSTS는 포트와 무관하게 호스트 전체에 적용되어 같은 PC의 `http://localhost:3000` 개발 서버까지 막힘
  - `includeSubDomains` 미사용: 회사 도메인의 다른 서비스까지 HTTPS를 강제하지 않도록 함
- 컨테이너 로그 순환 (2026-09-26): 서비스당 10MB × 5개. 기본 json-file 드라이버는 무제한이라 상시 운영 시 디스크를 채움
- 배포·운영 절차는 [deploy.md](deploy.md): 개발기 RDS(`sslmode=require`), 사내 DNS 이름 + 회사 인증서, 서버에서 `git pull` 후 빌드. 배포 전 점검 `scripts/preflight.sh`
- E2E 스택도 같은 Caddyfile로 앞단을 두어 전체 시나리오를 HTTPS 경로로 검증

```bash
docker compose up -d --build --wait
```

- 헬스체크 (2026-09-23): api는 8080 포트 개방(TCP), web은 `/` 응답으로 기동 완료 판정. web은 api 기동 완료 후 시작. `--wait`는 둘 다 통과할 때까지 대기
- 이미지: `api/Dockerfile` 멀티 스테이지 (eclipse-temurin 25 JDK 빌드 → 25 JRE 실행, 비root 사용자)
- web 이미지: `web/Dockerfile` 멀티 스테이지 (node:24-alpine, Next.js standalone 출력, 비root 사용자). `INTERNAL_API_URL=http://sentinel-api:8080`
- DB: 기본 로컬 PostgreSQL(`host.docker.internal:5432/sentinel`), RDS 전환은 `sentinel.env` 값 변경만으로 처리
- 스키마는 Flyway가 관리 (`api/src/main/resources/db/migration`). `sentinel` 스키마 미존재 시 생성, RDS처럼 이미 존재하면 마이그레이션만 적용
- 기동 시 `ddl-auto: validate`로 엔티티와 테이블 일치 검증. 불일치 시 기동 실패로 드리프트 조기 발견
- `update` 미사용 사유: 컬럼 삭제·변경 미반영으로 조용한 드리프트 발생, 데이터 존재 시 NOT NULL 컬럼 추가 불가, 변경 이력 부재, 공용 RDS에 앱이 DDL 직접 실행

### 유의사항
- TZ 지정 필수. 컨테이너 기본이 UTC라 미지정 시 크론이 9시간 어긋남
- 시크릿은 --env-file로 분리. 환경변수 직접 전달 시 docker inspect와 셸 히스토리에 잔존. sentinel.env는 .gitignore 등록
- 컨테이너 간 통신은 localhost가 아닌 컨테이너 이름 사용 (INTERNAL_API_URL=http://sentinel-api:8080)
- restart always는 무한 재시작 루프 가능. `unless-stopped` 사용(수동 중지 시 재시작 안 함). 초기 세팅 시 docker compose logs -f로 정상 기동 확인 후 진행. docker ps에 Restarting 상태로 표시되면 해당 상황
- 컨테이너 안의 localhost는 컨테이너 자신. 호스트의 PostgreSQL은 host.docker.internal로 접속 (compose에 `host-gateway` 매핑 명시)
- RDS 접속은 보안 그룹에 IP/VPN 대역 5432 인바운드 개방으로 해결. 터널 불필요

---

## 7. 구현 순서

1. 케이스 1건 실행 + 결과 저장 (Postman으로 검증, FE 없이)
2. 스케줄 실행 — 여기까지가 목적 달성 지점
3. Assertion 판정 + 실패 알림
4. FE 화면

### FE 4단계 구성 화면
- 케이스 등록/편집
- 스위트 구성
- 스케줄 설정 (크론 입력은 react-js-cron 등 활용, 다음 실행 시각 미리보기 포함)
- 실행 이력 목록
- 결과 상세 (요청/응답 원문)

### 원칙
- BE 우선. 화면이 얇고 엔진이 두꺼운 구조이므로 FE 선행 시 붙일 데이터 없음
- 실제 데이터가 쌓인 뒤 화면을 설계해야 재작업이 없음
- 케이스 등록을 SQL로 넣는 것이 번거로워지는 시점이 FE 착수 신호
- KPI 과제 특성상 범위 과욕 경계. 3단계까지 확실히 완성하는 편이 유리

---

## 8. 향후 확장 (문서상 계획으로만 명시)

### 스키마 드리프트 감지
반복 실행으로 축적된 이력을 기반으로 응답 구조 변화를 감지하는 기능. 1차 범위에서는 제외

- 응답 원문은 최근 30일 보관, 스키마 지문(JSON)은 영구 보관
- 지문은 값이 아닌 구조만 평탄화하여 저장. 비교가 집합 연산으로 단순화
- 배열은 전 요소 타입 유니온 병합 후 []로 통일
- null은 관측 누적으로 승격만 허용, 강등 금지
- 동적 키는 형제 키 임계치 초과 시 *로 축약
- 필수/선택은 present/observed 비율로 판정
- 드리프트 등급: BREAKING(필수 필드 소멸, 타입 변경) / WARNING(nullable 전환) / INFO(신규 필드)
- baseline 갱신은 INFO만 자동, BREAKING·WARNING은 승인 후 반영

### 기타
- OpenAPI / Postman 컬렉션 임포트를 통한 케이스 스켈레톤 자동 생성. OpenAPI 3 변환·가져오기 API와 스위트 화면의 가져오기 창 구현 (2026-09-23, `api/README.md` "케이스 가져오기"). 보안 스킴 → 인증 헤더 변수, 스키마 기반 본문 뼈대 포함. Postman Collection v2.0·v2.1도 지원 (평문 자격증명은 변수로 바꿔 케이스에 남기지 않음)
- 응답시간 추세 조회 (2026-09-24 구현, 스위트 화면의 케이스별 표와 일별 차트). 계획했던 일 단위 집계 테이블 대신 저장된 실행 결과에서 바로 집계
  - 케이스별 추세는 `idx_result_case_time`, 스위트 요약은 `idx_run_suite_time` → `idx_result_run`으로 조회. 케이스 1건 90일은 수천 행 수준
  - 원문 보관기간 정리가 소요 시간을 지우지 않아 원본이 남으므로 별도 테이블·집계 배치·재집계가 불필요
  - 대시보드 등에서 전체 스위트를 자주 집계해 느려지면 그때 일 단위 집계 테이블 도입
- 홈 대시보드 (2026-09-24): 스위트 요약(실패 실행 수 포함), 응답시간 변화(직전 같은 길이 기간 대비), 실패 실행 필터(`failing`)
  - 응답시간 추세와 같은 원칙으로 저장된 결과에서 바로 집계. 기간 규칙(시간대·기본 일수·최대 90일)은 `DatePeriod`로 공통화
- 결과 데이터 보관기간 파기 배치 (2026-09-23 구현). 실행·결과 행은 유지, 요청·응답 헤더·본문만 30일 경과 후 삭제 (`api/README.md` "결과 원문 보관기간")

---

## 9. 사전 확보 필요 데이터

착수 전 기록 필요. 완료 후에는 생성 불가

- 현재 API 검증 소요 시간 (주당 횟수 × 회당 소요 분)
- 검증 대상 엔드포인트 수
- 최근 API 변경으로 인한 이슈 건수

---

## 10. 미결 사항

- SSO 연동 가능 여부 (인증 방식 확정에 필요)
- 실패 알림 채널 (메신저 Webhook 규격). 확정 시 `Notifier` 구현체만 추가
- 개발환경 야간 가동 여부
- RDS 파라미터 그룹의 타임아웃 설정값
