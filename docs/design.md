# API 검증 자동화 테스트 에이전트 설계 정리

---

## 1. 개요

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
- 동시 수정 (2026-10-12, 독립 리뷰): 스케줄·환경·케이스에 낙관적 잠금(`@Version`, V30). Hibernate는 바뀐 엔티티의 모든 열을 쓰므로, 폴러의 다음 실행 시각 갱신이 사용자의 끄기·삭제를 되돌리거나 두 사람이 고친 시크릿 중 하나가 사라질 수 있었음. 충돌은 409(전용 문구), 화면은 "다른 곳에서 먼저 수정" 안내 후 재조회. 폴러는 충돌을 오류 알림 없이 다음 주기로
- API 문서 (2026-10-10): OpenAPI 문서 자동 생성 (`GET /api/openapi`, springdoc-openapi). 1단계 api, 2단계 web(화면)
  - REST 정리 뒤 CI를 연동하는 사용자가 README 대신 실제 API에서 경로·요청·응답을 확인하도록
  - 보는 곳·범위 (사용자 결정): 웹 화면에 직접 표시(같은 디자인, Swagger UI의 인라인 스타일 때문에 CSP 예외를 두지 않음), 로그인한 사용자만
  - 설명은 이미 있는 컨트롤러·레코드 Javadoc을 그대로 사용 (주석과 문서가 따로 놀지 않도록). 묶음 이름은 한국어 `@Tag`
  - 화면: 계정 영역의 "API 문서" (`/api-docs`). 묶음별 API를 접어 두고 펼쳐서 조건·본문·응답 확인, 타입은 데이터 형식으로 링크. 설명의 HTML은 그리지 않고 코드 표기만 유지(문서 내용을 HTML로 삽입하지 않음). "JSON 내려받기"로 다른 도구에 넣을 원본
- 관계 표기 (2026-10-09): 응답에서 다른 리소스를 가리킬 때는 `{id, name}` 객체. 숫자 ID만(`suiteId`) 또는 ID·이름 따로(`suiteId`+`suiteName`)를 섞어 쓰던 것을 통일. 요청 본문·조회 조건은 ID 그대로
  - 1차: 목록 리소스(환경·스위트의 `project`, 케이스의 `suite`, 스케줄의 `project`·`suite`·`environment`, 점검 시간의 `project`). 화면이 이름을 붙이려고 목록을 따로 받아 잇던 곳(스케줄·검색 화면)이 단순해짐
  - 목록은 가리키는 리소스를 한 번에 읽어 둔 뒤 이름을 채움 (`Preload`). 항목 수만큼 조회하지 않는지 `RelationQueryCountTest`가 확인
  - 2차: 실행 상세(`suiteId`+`suiteName` 등 → `project`·`suite`·`environment`, 결과의 `testCase`), 응답시간 추세·요약(`testCase`·`suite`·`environment`). CI 스크립트(`scripts/sentinel-run.sh`)의 실패 케이스 이름도 `.testCase.name`으로
  - 이름이 없는 실행을 가리킬 때는 ID 그대로 (`rerunOf`). 직전 실행은 `{id, startedAt}`
  - 3차: 변경 이력(`targetType`·`targetId`·`targetName` → `target: {type, id, name}`, `projectId`·`suiteId` → `project`·`suite`, 프로젝트·스위트는 지금 이름), 케이스 가져오기 결과(`suite`), 재설정 링크 확인(`user`). `RestConventionTest`가 응답 레코드에 `xxxId`·`xxxName` 필드가 없는지 확인 (새 API가 어기면 빌드 실패)
- 계정 리소스 정리 (2026-10-09): 한 개념이 두 경로로 나뉘던 것을 정리. 초대는 `/api/invites`(발급 `POST`, 확인 `GET /{token}`), 재설정 링크는 `/api/password-resets`(발급 `POST {userId}`, 확인 `GET /{token}`). 초대 수락은 계정을 만드는 것이므로 `POST /api/users`(초대 토큰), 비밀번호는 사용자의 하위 리소스로 `PUT /api/users/me/password`(현재 비밀번호)·`PUT /api/users/{id}/password`(재설정 토큰). 이전 `POST /api/users/invites`·`POST /api/invites/{token}`·`POST /api/users/{id}/password-resets`·`POST /api/password-resets/{token}`·`POST /api/users/me/password`
  - 초대 수락은 계정만 만들고 로그인은 `POST /api/sessions`로 따로 (리소스 생성 응답에 세션을 섞지 않음). 화면(BFF)이 두 요청을 이어서 처리
  - 인증 필터의 로그인 없이 허용하는 요청을 경로 접두어에서 "메서드 + 경로" 목록으로 좁힘 (같은 경로의 다른 메서드는 인증 필요)
- 알림 리소스 정리 (2026-10-09): 연결 확인 알림은 발송 이력을 만드는 것이므로 `POST /api/notification-deliveries`(201 + 발송 기록) 하나로 (이전 `POST /api/projects/{id}/test-notifications`·`POST /api/test-notifications`). 채널 상태는 채널을 리소스로 `GET /api/notification-channels` (이전 `GET /api/notification-channel-health`). 같은 저장소의 화면만 쓰는 주소라 옛 주소를 남기지 않고 api·web을 한 번에 교체
- 단건 조회 (2026-10-09): ID로 수정·삭제할 수 있는 리소스는 그 주소로 조회도 가능 (`Location`이 실제로 조회되는 주소). 휴일·점검 시간·API 토큰·사용자 단건 조회 추가. `RestConventionTest`가 경로 규칙(`/api/` 아래 소문자·하이픈)과 함께 확인
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
  - 키 교체 (2026-10-10): 이전 키(`SENTINEL_SECRET_KEY_PREVIOUS`)를 함께 지정하고 기동하면 저장된 값을 새 키로 다시 암호화 (`api/README.md` "환경"의 키 교체, `docs/deploy.md` "암호화 키 교체")
    - 키 유출·키를 아는 사람이 바뀔 때 값을 모두 다시 입력하지 않도록. 실제 시크릿·Webhook이 쌓이는 서버 배포 전에 도입
    - 별도 명령·API 대신 기동 시 재암호화: 키가 환경변수로만 주어지고, 요청·스케줄 시작 전에 끝나 화면의 동시 수정과 겹치지 않음 (환경 시크릿은 JSON 한 칸이라 동시 수정 시 덮어쓰기 위험)
    - 저장 형식에 키 ID를 넣지 않음: GCM 인증 태그로 키 불일치를 확실히 구분하므로 차례로 시도해도 안전, 기존 값 형식 변환 불필요
    - 풀 수 없는 값은 지우지 않음: 이전 키를 잘못 넣은 경우에도 올바른 키로 다시 기동하면 복구 가능
- Spring 주소는 서버 전용 환경변수로 관리. NEXT_PUBLIC_ 접두어 사용 시 브라우저 번들에 내부 주소 노출

---

## 3. 기술 스택

| 구분 | 선택 |
|------|------|
| Java | 25 |
| BE | Spring Boot 4.1 (Spring Framework 7.0.8 기반) |
| ORM | JPA + QueryDSL 7.7 (OpenFeign 포크). 조건 조회·일괄 변경은 Q-타입으로 조립 (2026-09-28 `@Query` 문자열에서 전환, 아래 "조회 작성 방식") |
| DB | PostgreSQL — AWS RDS의 sentinel 스키마 |
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
- 응답 값 추출 규칙도 같은 방식으로 test_case.extractors JSON 단일 컬럼 (V11, 2026-09-27)

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
  - 성능 점검 도구(`scripts/perf/seed-history.sql`)의 `generate_series`·`random`: 앱 SQL이 아닌 대량 생성 도구
- 표준 집계·시간대 문법 사용: 백분위 `PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY ...)`, 날짜 변환 `CAST(... AT TIME ZONE ... AS DATE)`
- 네이티브 쿼리의 테이블은 `{h-schema}`(Hibernate 기본 스키마 치환)로 한정. 네이티브 쿼리에는 `hibernate.default_schema`가 적용되지 않아 JDBC URL의 `currentSchema`에 의존하지 않도록 함

### 조회 작성 방식 (2026-09-28)
- JPQL `@Query` 19건(일괄 변경 5건 포함)과 `Specification` 2건을 QueryDSL로 전환 (호출처 없는 만료 세션 삭제 1건은 삭제). 문자열 쿼리는 엔티티 필드 이름을 바꿔도 컴파일되고 실행 시점에야 실패하므로 Q-타입으로 컴파일 시점에 확인
- 원본 `com.querydsl`은 5.1.0 이후 새 릴리스가 없어 Hibernate 7 대응을 보장할 수 없으므로, 유지보수 중인 OpenFeign 포크(`io.github.openfeign.querydsl`) 사용. Spring Data의 `QuerydslPredicateExecutor` 대신 저장소별 사용자 정의 조각(`XxxQueries` + `XxxQueriesImpl`)으로 조인 로딩·정렬·건수 쿼리를 직접 지정
- 파생 쿼리 메서드는 조건 1~2개의 단순 조회만. 조건이 많아 이름이 긴 파생 쿼리(`findBySuiteIdAndEnvironmentIdAndTriggerAndStatusAndIdLessThanOrderByIdDesc` 등)는 의도를 드러내는 이름(`findPreviousRuns`)의 QueryDSL 메서드로 교체
- 네이티브 SQL 4건 유지: 응답시간 백분위(`PERCENTILE_CONT ... WITHIN GROUP`) 3건, 구조 지문 기준선(`LATERAL` + `FETCH FIRST`) 1건. JPQL·QueryDSL에 대응 문법 없음
- 전환 후 생성 SQL을 전환 전과 비교: 조건·정렬·`FETCH FIRST` 동일. 직전 실행 조회의 시작 시각 상한이 인덱스 범위 조건으로 유지됨을 45일치 이력 실행 계획으로 확인 (0.08ms)
- 실행 목록의 스위트·프로젝트 조건은 조인 로딩한 테이블의 ID로 생성(`s.id = ?`). PostgreSQL이 조인 조건으로 `test_run.suite_id` 조건을 도출하여 같은 인덱스(`idx_run_suite_time`) 사용 확인

```sql
CREATE SCHEMA sentinel;
CREATE USER sentinel_app WITH PASSWORD '...';
GRANT USAGE, CREATE ON SCHEMA sentinel TO sentinel_app;

CREATE INDEX idx_result_run       ON test_result(run_id);
CREATE INDEX idx_result_case_time ON test_result(test_case_id, id DESC);
CREATE INDEX idx_run_suite_time   ON test_run(suite_id, started_at DESC);
CREATE INDEX idx_run_time         ON test_run(started_at DESC, id DESC);
```

공용 RDS이므로 스키마 한정 권한 계정 사용. Flyway가 sentinel 스키마 안에 테이블과 이력 테이블(flyway_schema_history)을 생성하므로 스키마 내 CREATE 권한 필요

---

## 5. 러너 및 스케줄 설계

### 패키지 구조
```
com.xxx.apitest
 ├─ domain/          엔티티, 도메인 서비스
 ├─ runner/          RequestExecutor, VariableResolver
 │   ├─ assertion/   AssertionEvaluator + 구현체
 │   ├─ extract/     Extractor (응답 값 추출)
 │   └─ schema/      ResponseSchema, SchemaBaseline, SchemaDrift (구조 지문·변화)
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
  - 외부 저장소 장애 대비 (2026-10-10): Gradle 플러그인 저장소 일시 장애로 E2E 이미지 빌드가 실패한 뒤 도입
    - api 플러그인과 그 의존성은 Maven Central 먼저, 없으면 Gradle 플러그인 저장소 (`api/settings.gradle`). Central만으로 모두 풀리는 것 확인
    - E2E의 api·web 이미지는 GitHub Actions 캐시를 쓰는 빌더(`docker/build-push-action`)로 미리 빌드 후 스택 기동. `build.gradle`·`package-lock.json`이 그대로면 의존성 단계 재사용 (외부 다운로드 없음)
    - 로컬 `npm run e2e:up`은 그대로 compose가 빌드 (이미지 이름만 `sentinel-e2e-api`·`sentinel-e2e-web`으로 고정)

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
- 독립 리뷰 반영 (2026-10-12)
  - 기동 시 남은 실행 정리는 모든 빈 생성 직후: 기동 완료 이벤트에서 하면 먼저 시작한 첫 폴링(컨텍스트 갱신 이벤트에 시작)의 따라잡기 실행까지 중단됨
  - 스케줄마다 그 차례의 시각으로 판단: 폴링 시작 시각을 계속 쓰면 앞 스위트가 오래 걸릴 때 점검 시간을 놓치고(점검 중 실행·실패 알림), 다음 실행 시각이 과거 기준이라 연달아 실행됨
  - 실행 도중 예상하지 못한 오류는 조건부 갱신(진행 중일 때만)으로 중단 처리 후 다시 던짐. 재기동 전까지 RUNNING으로 남아 다시 실행·원문 정리·집계가 막히던 문제

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
  - 본문은 `{"text": "..."}` 한 필드로 Slack·Teams 수신 Webhook 그대로 사용. 다른 형식은 구현체 추가로 대응 → 2026-10-04 채널 서식 추가 (아래)
  - 주소는 자격증명이므로 환경변수 주입, 로그 미기록
  - 타임아웃은 짧게(연결 3s, 응답 5s) 설정하여 알림이 실행·폴링을 지연시키지 않도록 함
  - `sentinel.notification.web-base-url` 지정 시 실패 알림에 결과 화면 링크(`결과: {주소}/runs/{id}`) 추가. 알림에서 바로 원인 확인 가능
- 발송 대상: 스케줄 실행 결과의 판정 실패·오류, 스케줄 실행 자체 오류, DB 연결 실패·복구, 모니터링 공백, 주간 요약, 응답시간 악화·회복. 수동 실행은 화면·API 응답으로 확인하므로 미발송
- 실패 사유는 실패한 판정 규칙만 표시(`status in [2xx]: was 500`), 최대 20줄 후 `… 외 N건`
- 알림 발송 실패는 로그만 기록하고 폴링 계속
- 연속 실패·복구 알림 (2026-09-24)
  - 매번 같은 모양의 실패 알림은 새 장애와 이어지는 장애를 구분할 수 없고, 해결 여부는 화면을 열어야 알 수 있었음
  - 케이스마다 `[새로 실패]`·`[N회째 · 시작 시각부터]` 표시, 제목에 `(새로 실패 K건)`·`(계속 실패 중)`. 새로 실패한 케이스를 앞에 배치
  - 직전 스케줄 실행에 실패가 있었고 이번에 모두 통과하면 복구 알림 1회 (다시 통과한 케이스, 실패 지속 횟수·기간)
  - 비교 대상은 같은 스위트·환경의 이전 스케줄 실행(완료만, 최대 20회). 수동 실행은 수정 확인용인 경우가 많아 제외 (포함하면 복구 알림 누락 가능)
  - 반복 실패 알림 억제(같은 실패는 N회마다 1번 등)는 미적용. 실제 알림 빈도를 본 뒤 검토 → 2026-10-03 적용 (아래)
- 계속 실패 중 재알림 (2026-10-03): 새로 실패한 케이스 없이 계속 실패 중이면 같은 스위트·환경의 마지막 실패 알림부터 1시간(`sentinel.notification.repeat-interval`)마다만 발송 (`test_run.failure_notified_at` V26, `api/README.md` "계속 실패 중 재알림"). api 1단계
  - 10분 간격 스케줄이면 장애 하나에 시간당 6건. 반복되는 같은 알림은 무시하게 되고 새 장애 알림이 묻힘 (주간 요약에서 일주일 내내 실패한 스위트 확인)
  - 간격 (사용자 결정): 1시간. 장애가 이어지는 중임을 잊지 않을 정도
  - 일부 복구 (사용자 결정): 바로 알리지 않고 다음 재알림에 남은 실패만. 알림 수가 가장 적음
  - 새로 실패·복구는 항상 바로 (새 정보). 통과를 사이에 둔 재실패도 새로 실패
  - 연속 실패 판단(이전 20회)과 별도로 발송 기록을 실행에 남김: 1분 간격 스케줄이면 1시간이 60회라 20회 확인으로는 마지막 발송을 알 수 없음. 조회는 시작 시각 하한으로 간격 안의 실행만 읽음
- 알림 발송 이력 (2026-10-05): 보낼 때마다 시각·채널·제목·결과 기록 (`notification_delivery` V29, `GET /api/notification-deliveries`, `api/README.md` "발송 이력"). 1단계 api, 2단계 web
  - 알림 종류가 늘며 "알림이 안 왔다"를 확인하려면 서버 로그를 봐야 했음. 서버 배포 뒤 문의에 바로 답할 곳
  - 보관·권한·내용 (사용자 결정): 90일(분기 단위 확인), 모든 사용자 조회, 제목·결과만 (본문은 채널에서 확인, 주소는 자격증명이라 제외)
  - 채널 구현이 결과를 기록 객체(`DeliveryLog`)에 넘기고, 기록은 발송 뒤 별도 트랜잭션. 기록 실패는 삼킴: `Notifier`는 DB 연결 실패 알림에도 쓰여 DB에 의존하면 안 됨 (그 알림의 기록은 남지 않을 수 있음)
  - 주소 미설정(`LOG`)도 기록: 알림이 채널에 가지 않은 이유가 설정 누락임을 화면에서 확인
  - 화면 (2026-10-05): 사이드바 "알림 이력"(`/notifications`). 결과 옆에 확인할 점(404·410 주소 변경, 401·403 거부, 429 한도, 5xx 채널 오류, 연결 실패는 내부망·프록시)을 붙여 로그 없이 원인 짐작. 프로젝트 화면 알림 채널에서 이동
    - 좁은 화면에서도 제목이 눌리지 않도록 열은 "알림(시각·보낸 곳은 제목 아래)"과 "결과" 둘
    - 프로젝트 필터는 프로젝트 Webhook으로 보낸 것만: 기본 채널 알림은 여러 프로젝트를 묶기도 해 프로젝트로 나누지 않음
  - 채널 고장 표시 (2026-10-05): 알림을 보내지 못하는 채널을 대시보드에 (`GET /api/notification-channels`의 `failing`, 2026-10-09 이전 `notification-channel-health`). 1단계 api, 2단계 web
    - Webhook이 삭제·변경되면 실패 알림도, 그 사실을 알릴 알림도 같은 채널로 가서 아무도 모름. 이력 화면은 일부러 열어야 보임
    - 기준 (사용자 결정): 채널별 최근 7일 안 마지막 발송이 실패. 고친 뒤 한 번 성공하면 바로 사라짐 (최근 24시간 실패 건수 기준은 고친 뒤에도 남음)
    - 지금 쓰지 않는 채널(Webhook 삭제·프로젝트 삭제·기본 채널 미설정)은 제외: 고칠 수 없는 경고가 남지 않도록
    - 화면 (2026-10-06): 대시보드 맨 위(점검 중인 환경보다 위) "알림을 보내지 못하는 채널". 다른 모든 알림이 이 채널에 기대므로 가장 먼저. 채널을 누르면 그 채널의 보내지 못한 알림 이력
    - 기본 채널 주소 미설정은 고장이 아니라 설정 상태라 경고 패널 대신 머리 아래 한 줄 (서버 배포 전 확인용)
  - 기본 채널 연결 확인 (2026-10-09): 관리자가 기본 채널로 테스트 알림 (`POST /api/notification-deliveries` 프로젝트 없이, 2026-10-09 이전 `POST /api/test-notifications`). 1단계 api, 2단계 web
    - 배포 안내 점검에서 발견: 기본 채널은 첫 알림(월요일 주간 요약 등)이 갈 때까지 받는지 알 수 없었음
    - 실제 알림과 같은 발송 경로(`WebhookNotifier`)로 보내고 이력에 기록: 설정 확인이 곧 실제 경로 확인. 성공하면 채널 고장 표시도 사라짐
    - 모든 프로젝트에 걸친 채널이라 관리자만 (프로젝트 연결 확인은 모든 사용자)
    - 화면 (2026-10-09): 알림 이력 화면 맨 위 "기본 알림 채널"(관리자). 프로젝트 화면 알림 채널과 같은 버튼·결과 문구. 주소가 없으면 버튼 대신 설정 안내. 배포 안내(`docs/deploy.md`)의 확인 절차도 이 버튼으로

---

### 보안 점검 (2026-09-24)

인증·세션·권한과 공격면 점검 결과

| 항목 | 결과 | 조치 |
|------|------|------|
| 로그인 후 이동 주소(`from`) | `/\evil.example`을 브라우저가 `//evil.example`로 해석해 다른 사이트로 이동 (확인) | URL로 해석해 같은 출처 경로만 허용 |
| 실행기 목적지 | 클라우드 메타데이터(169.254.169.254) 접근 가능. 클라우드 배포 시 인스턴스 자격증명 노출 경로 | 링크 로컬·메타데이터 주소 전송 거부 (`BLOCKED_DESTINATION`). 2026-09-26 확인한 주소로만 연결하도록 보완 (DNS rebinding 차단) |
| 로그인 반복 실패 | 제한 없음 (비밀번호 대입 가능) | 이메일별 15분 5회 실패 시 15분 잠금 (429) |
| BFF 프록시 | 사용처가 없는데 쓰기 메서드까지 쿠키 인증으로 전달, 출처 확인 없음 | 조회 전용으로 제한 |
| 인증 필터 경로 판단 | 점 세그먼트·`;`·인코딩된 `.`·`/` 우회 없음. **2026-10-12 독립 리뷰에서 우회 발견**: 글자를 인코딩한 경로(`/%61pi/runs`)는 원문이 `/api/`로 시작하지 않아 인증 없이 통과(Tomcat·Spring은 풀어서 `/api/`로 처리). HTTPS 앞단의 API 토큰 경로로도 가짜 토큰으로 통과 | 필터에 온 요청은 모두 API로 보고 인증 요구(필터는 `/api/*`에만 등록), 퍼센트 인코딩이 있는 경로는 로그인 전 요청으로 보지 않음. 실제 Tomcat 대상 회귀 테스트 |
| 세션·링크 토큰 | 32바이트 난수, SHA-256 해시 저장, 만료·1회용. **2026-10-12 독립 리뷰**: 다시 보낸 초대의 예전 링크가 남아 계정 삭제 뒤 재가입 가능, 재설정 뒤에도 API 토큰 유지 | 초대 수락 시 같은 이메일의 다른 초대 무효, 사용자 삭제 시 남은 초대 삭제, 재설정(계정 복구) 시 API 토큰 폐기 |
| 세션 쿠키 | `HttpOnly; Secure; SameSite=Lax`, 스크립트로 읽을 수 없음. **2026-10-12 독립 리뷰**: 접두어가 없어 같은 상위 도메인의 다른 사이트가 쿠키를 심어 공격자 계정에 로그인시킬 수 있음(cookie tossing) | 운영은 `__Host-sentinel_session` (로그아웃 삭제도 같은 속성. 속성 없이 지우면 브라우저가 무시해 쿠키가 남는 것을 E2E로 발견) |
| 로그인 응답 | 없는 계정도 같은 문구. **2026-10-12 독립 리뷰**: 더미 해시가 61자(형식 오류)라 비교 없이 즉시 실패해 응답 시간으로 계정 유무가 드러남 | 기동 시 만든 실제 BCrypt 해시와 비교, 형식 테스트 |
| XSS | `dangerouslySetInnerHTML` 미사용, 응답 본문은 텍스트로 표시 | 유지 |
| CSP | 미적용 (XSS 발생 시 2차 방어 없음) | 요청별 nonce + `'strict-dynamic'` 정책 적용 (2026-09-25, `web/README.md` 콘텐츠 보안 정책) |
| 화면 앞단·링크 화면 | **2026-10-12 독립 리뷰**: 앞단 제외 규칙(`api`)이 `/api-docs`까지 빼 CSP 없음, 초대·재설정 화면이 풀린 토큰("../users/1")을 그대로 api 경로에 붙임, `deleteUser`가 바인딩 인자를 검증하지 않음 | 제외 규칙을 `api/`로, 토큰 인코딩(`lib/api/link-paths.ts`), 양의 정수 ID만 |
| 실행기 응답 | **2026-10-12 독립 리뷰**: 응답 본문을 크기 제한 없이 메모리로 읽고, 읽을 때마다 다시 재는 대기 시간만 있어 조금씩 보내는 응답이 실행·수동 실행 슬롯을 붙잡을 수 있음 | 본문 최대 10MB(초과분은 받지 않고 연결 끊음), 본문 전체 제한 2분(넘으면 연결 끊고 TIMEOUT). 알림 Webhook도 같은 실행기 |
| 시크릿 | AES-GCM 저장, 이름만 노출, 이력은 마스킹. **2026-10-12 독립 리뷰**: 한 시크릿이 다른 시크릿의 일부면 가리는 순서에 따라 나머지가 드러남 | 긴 값부터 가림 |

후속 검토
- 로그인 잠금은 메모리 보관. 여러 인스턴스로 늘리면 공유 저장소 필요

### 의존성 취약점 점검 (2026-10-10)

- 방식 (사용자 결정): Dependabot과 CI 점검을 함께. 비공개 저장소라 의존성 검토(dependency-review)·CodeQL은 유료 기능(GitHub Advanced Security)이 필요해 제외
  - Dependabot (`.github/dependabot.yml`): 매주 월요일 생태계(Gradle·npm·Actions·Docker·compose)마다 minor·patch를 한 PR로. 큰 버전 이미지(Java·Node LTS)는 직접 결정하므로 제외. 취약점 경고·보안 수정 PR은 저장소 설정에서 켜야 동작
  - CI 점검 (`.github/workflows/security.yml`): api·web 이미지를 OSV-Scanner로(실제 배포물 안의 Java 라이브러리·OS 패키지), web 운영 의존성은 npm audit으로. Next.js standalone 이미지에는 npm 패키지 정보가 남지 않아 이미지 점검으로는 보이지 않음
- 막는 시점 (사용자 결정): 의존성을 바꾼 PR(`build.gradle`·`package-lock.json`·Dockerfile 등)만 막고, 매주 월요일 정기 점검은 실패로 표시. 새 취약점이 공개되는 날 무관한 PR까지 막히지 않도록
- 막는 기준 (`scripts/vulnerabilities.sh`): 애플리케이션 의존성(Java 라이브러리·npm 운영 의존성)의 높음(CVSS 7.0·high) 이상 중 수정 버전이 있는 것. OS 패키지는 보고만 (대부분 수정판이 없고, 있어도 기본 이미지 갱신으로만 반영)
- OSV-Scanner는 공식 릴리스 실행 파일을 버전·체크섬 고정으로 사용 (외부 액션 미사용)
- 점검 누락 방지 (2026-10-14, 독립 리뷰): 기본 출력은 취약점 있는 패키지만 담아, 점검기가 jar를 읽지 못해도 0건으로 통과. `--all-packages`로 출력하고 판정에서 이미지마다 있어야 할 생태계(api Maven, web Alpine, Caddy Go)가 없으면 실패(`--expect`). 요약에 이미지별 점검 패키지 수 표시
- HTTPS 앞단(Caddy) 이미지도 점검 (2026-10-14): Go 모듈·OS 패키지라 보고만. 수정은 Caddy 새 버전 이미지로만 가능(Dependabot docker-compose). `docker-compose.yml`이 바뀐 PR도 점검
- 정기 점검 실패 알림 (2026-10-14): 실패 메일은 cron을 마지막으로 바꾼 사람에게만 가므로 저장소 이슈("Weekly security check failed")를 열고, 다시 통과하면 닫음. 공개 저장소는 60일 동안 활동이 없으면 GitHub이 정기 실행을 멈추므로 Actions 탭의 안내 확인 (docs/deploy.md "업데이트")
- 공급망 고정 (2026-10-14, 독립 리뷰)
  - GitHub Actions는 커밋 SHA로 고정하고 버전은 주석(`# v7.0.1`). 태그는 옮길 수 있어 같은 이름으로 다른 코드가 실행될 수 있음. Dependabot이 SHA와 주석을 함께 갱신
  - Gradle 배포판 체크섬 고정(`distributionSha256Sum`). CI의 setup-gradle은 wrapper jar만 검증
  - api Docker 빌드의 의존성 단계에서 jar까지 받고 컴파일은 네트워크 없이. `dependencies` 작업은 POM만 받아 캐시가 맞아도 컴파일 때 Maven Central에서 jar를 받았음 (2026-10-12 CI e2e에서 Maven Central 일시 404로 실패)
- 작업 셸을 `bash`로 지정 (2026-10-11): 지정하지 않으면 `bash -e`로 실행되어 `pipefail`이 없음. 판정 결과를 `tee`로 요약에 남기는 단계가 판정 실패를 무시하고 통과했음(#142에서 24건을 찾고도 통과)
- 도입 시 발견 (2026-10-10): api `spring-webmvc` 7.0.8 치명(9.8) 2건, Jackson·httpcore5 높음, web `next` 16.3.5 치명과 `sharp` 등 높음. 모두 수정 버전 있음 → 의존성 업데이트로 해결
  - 해결 (2026-10-11): Spring Boot 4.1.1(`spring-webmvc` 7.0.9), Jackson은 Spring Boot가 아직 취약한 버전을 관리해 같은 minor 수정판으로 덮어씀(`build.gradle`의 `jackson-bom.version`·`jackson-2-bom.version`, 관리되면 제거). web은 `next` 16.4.0, `sharp`·`source-map-js`는 범위 안 업데이트
  - `shadcn`은 개발 의존성으로: 빌드 때 CSS(`shadcn/tailwind.css`)만 쓰는 CLI. 올려서 고칠 버전이 없는 취약점(2.0.0 이상 전부)이 딸린 패키지와 함께 운영 점검 대상에서 빠짐
  - api·web 이미지를 한 점검에서 함께 판정하므로 둘을 한 PR로 (한쪽만 고친 PR은 다른 쪽 때문에 통과할 수 없음)

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
- 인증서는 `SENTINEL_TLS`로 선택: 내부 주소는 내부 인증 기관(루트 인증서 PC별 신뢰 등록), 공개 도메인은 Let's Encrypt, 보유 인증서 파일도 가능
- web·api는 `127.0.0.1`에만 공개하여 네트워크에서는 HTTPS 앞단만 접근. 평문 HTTP로 비밀번호·토큰이 오가는 경로 차단
- Host 헤더를 그대로 전달하여 Next.js Server Action의 출처 확인(Origin과 Host 비교)이 추가 설정 없이 동작
- 보안 헤더: `X-Content-Type-Options: nosniff`, `Referrer-Policy: strict-origin-when-cross-origin`, `X-Frame-Options: DENY`, `Server` 제거
- HSTS (2026-09-26): 보유 인증서(기관 발급)로 결정되어 적용. `SENTINEL_HSTS_MAX_AGE`로 켜며 기본 0(미사용)
  - 내부 인증서와 함께 쓰면 신뢰 등록하지 않은 PC가 경고를 넘길 수 없으므로 신뢰된 인증서일 때만 사용
  - localhost 제외: HSTS는 포트와 무관하게 호스트 전체에 적용되어 같은 PC의 `http://localhost:3000` 개발 서버까지 막힘
  - `includeSubDomains` 미사용: 같은 도메인의 다른 서비스까지 HTTPS를 강제하지 않도록 함
- 컨테이너 로그 순환 (2026-09-26): 서비스당 10MB × 5개. 기본 json-file 드라이버는 무제한이라 상시 운영 시 디스크를 채움
- 배포·운영 절차는 [deploy.md](deploy.md): AWS RDS(`sslmode=require`), 내부 DNS 이름 + 보유 인증서, 서버에서 `git pull` 후 빌드. 배포 전 점검 `scripts/preflight.sh`
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
- 시크릿은 env_file(sentinel.env)로 분리. 환경변수 직접 전달 시 셸 히스토리에 잔존. sentinel.env는 .gitignore 등록
- env_file 값 해석 (2026-10-13 확인, Compose v5.6): 따옴표 없는 값·큰따옴표 값의 `$이름`은 변수 치환(`pa$word` → `pa`), 따옴표 없는 값의 ` #` 뒤는 주석. 작은따옴표 값만 그대로 전달. `docker run --env-file`(따옴표까지 값)과 규칙이 달라 예전 "따옴표 금지" 안내가 틀렸음. `scripts/preflight.sh`가 키 이름만 출력하여 점검
- 업데이트는 `docker compose pull --ignore-buildable` + `build --pull`로 베이스 이미지 보안 패치 반영. `up --build`만으로는 서버에 남은 이전 베이스 이미지 사용
- E2E 스택 포트는 127.0.0.1에만 개방. 고정된 테스트 관리자 계정·암호화 키가 공개 저장소에 있으므로 다른 PC 접근 차단
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
- 범위 과욕 경계. 3단계까지 확실히 완성하는 편이 유리
- 셸 스크립트가 출력하는 문구는 영어 (2026-10-11, 사용자 결정): 서버·CI 로그에서 그대로 읽히고 다른 도구와 함께 보기 쉽도록. 주석은 한국어 유지. `sentinel-run.sh`의 사용법은 맨 위 주석을 출력하므로 그 주석도 영어

---

## 8. 향후 확장 (문서상 계획으로만 명시)

### 스키마 드리프트 감지
반복 실행으로 축적된 이력을 기반으로 응답 구조 변화를 감지하는 기능. 1차 범위에서는 제외

- 1단계 구조 지문 수집 (2026-09-26 구현): 실행마다 `test_result.response_schema`(V9)에 경로별 관측 타입 저장, 결과 상세의 "구조" 탭. `runner.schema.ResponseSchema`(순수 자바)
  - 실제 사용 전부터 기준 데이터를 쌓기 위해 비교·알림보다 먼저 도입
  - 동적 키 축약에 형제 키 수 기준(50개 초과)과 함께 키 형태 기준(2개 이상이면서 모두 숫자·UUID·날짜) 추가. ID를 키로 쓰는 작은 객체도 축약
- 2단계 구조 변화 판정 (2026-09-27 구현): `runner.schema.SchemaBaseline`·`SchemaDrift`(순수 자바), 실행 상세의 "구조 변화" 배지·목록
  - 기준선은 저장하지 않고 조회 시점에 이전 실행 지문 최근 20건으로 계산. 기준값(창 크기·최소 관측 수)은 설정으로 분리하여 배포 후 실제 관측치로 조정
  - 필수 판정의 관측 수는 상위 경로가 있었던 응답만 셈 (present/observed). 빈 배열·선택 상위 때문에 하위가 선택으로 판정되는 것 방지
  - null 승격만 허용·강등 금지는 창 안에서만 보장 (null 관측이 창 밖으로 밀려나면 다시 null 없음으로 계산). 영구 보장은 기준선 저장(3단계)
  - 알림 미적용: 기준값이 실제 데이터로 검증되기 전이라 판정 오류가 알림 소음이 됨
  - 호환이 깨지는 변화(BREAKING)가 있는 케이스는 판정이 통과여도 결과 상세에서 펼친 상태로 표시
  - 다음 단계(3단계): 기준선 저장·승인(INFO 자동 반영, BREAKING·WARNING 승인), 알림

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
- 케이스 단건 실행 화면 (2026-09-26): 스위트 화면 케이스 행의 "⋯" 메뉴, 케이스 등록·수정 화면의 "등록 후 실행"·"저장 후 실행"(수정 중인 내용을 저장한 뒤 실행).
  - 등록 후 실행에서 실행만 실패하면 다시 눌러 같은 케이스가 또 등록되지 않도록 새 케이스의 수정 화면으로 이동해 안내. 수정은 그대로 두고 오류 표시 실행에 대상 케이스(`test_run.test_case_id`, V8)를 남겨 스위트 전체 실행과 구분
  - 스위트 단위 집계(대시보드 스위트 요약·최근 실행)는 스위트 전체 실행만. 케이스 단위 집계(응답시간)와 실행 이력은 모든 실행
- 케이스 복제 (2026-09-27): 스위트 화면 케이스 행의 "⋯" 메뉴. 요청 정의·판정 규칙·값 추출을 복사해 같은 스위트에 "원본 (복사)"로 등록하고 새 케이스 수정 화면으로 이동
  - 이름은 스위트 안에서 비어 있는 "(복사 n)"까지 번호 증가, 최대 길이(200)를 넘으면 원본 이름 쪽을 자름
  - web Server Action이 조회·등록 API를 조합 (URL에 동사를 넣지 않는 REST 원칙 유지)
  - 복제본은 원본 바로 다음 실행 순서 (2026-09-28, 등록 API의 `afterCaseId`). 실행 순서가 생긴 뒤 목록 끝에 붙으면 원본과 떨어져 값 추출 흐름에서 위치를 다시 옮겨야 했음. 등록과 같은 트랜잭션에서 뒤 케이스 순서를 밀어 순서가 어긋나는 중간 상태 없음
- 환경 복제 (2026-09-27): 프로젝트 화면 환경 행의 "⋯" 메뉴. 바로 만들지 않고 원본의 기본 URL·변수와 복제본 이름을 채운 등록 창을 열어 이름·URL을 고친 뒤 등록 (dev → staging처럼 대부분 값을 바꾸므로)
  - 시크릿은 암호화되어 값을 꺼낼 수 없으므로 이름만 채운 입력 행으로 두고 값 재입력. 빈 시크릿 값은 입력 칸 필수 확인과 Server Action 양쪽에서 거부 (조용히 빈 시크릿이 저장되는 것 방지)
- 대용량 이력 성능 점검 (2026-09-27): 1년치 이력으로 화면이 쓰는 API 응답 시간 측정·개선 (측정 방법 `scripts/perf/README.md`)
  - 규모: 프로젝트 4 × 스위트 5 × 케이스 10, 스위트마다 10분 간격 1년 → 실행 105만·결과 1,046만 건(DB 5.3GB). 최근 30일만 원문 보관
  - 원인: 실행 목록의 실패·오류 조건(결과 EXISTS), 대시보드 판정 수(결과 조인 집계), 실행 목록의 판정 수, 구조 변화 기준선(케이스 이력 전체에 창 함수)이 이력에 비례하여 결과 테이블을 읽음. 스위트 조건 없는 최신순 목록은 인덱스 없음
  - 실행별 판정 수(`test_run.passed_count`·`failed_count`·`error_count`, V10): 결과를 저장하는 트랜잭션에서 함께 올림. 실행 도중 앱이 멈춰도 저장된 결과와 일치. 실패 조건·대시보드·목록은 실행 행만 읽음
  - `idx_run_time (started_at DESC, id DESC)`: 스위트 조건 없는 최신순 목록·기간 조건
  - 구조 변화 기준선: 케이스마다 `LATERAL` + `FETCH FIRST`(SQL 표준)로 `idx_result_case_time`을 이 실행의 첫 결과 직전부터 창 크기만큼만 읽음. 결과 ID 상한이 없으면 오래된 실행 조회 시 그 뒤 결과를 모두 거쳐 감 (180일 전 실행: 상한 있음 1.5ms, 없음 646ms)
  - 검토 후 제외: `test_result (verdict, run_id)` 인덱스는 실패 조건이 오히려 느려짐(648 → 1,301ms). 일 단위 집계 테이블은 판정 수 보관만으로 목표 달성하여 불필요
  - V10 적용: 1년치 기준 9.4초 (기존 실행 판정 수 채우기, 결과 합계와 일치 확인). 전 행 갱신으로 `test_run` 크기가 일시적으로 늘어나며 autovacuum이 회수

  | API (중앙값, 5회) | 개선 전 | 개선 후 |
  |------|--------:|--------:|
  | 대시보드 스위트 요약 7일 / 30일 | 137 / 913ms | 52 / 55ms |
  | 대시보드 최근 실패 (7일) | 47ms | 13ms |
  | 실행 이력 첫 페이지 / 마지막 쪽 페이지 | 201 / 193ms | 37 / 100ms |
  | 실행 이력 실패만 | 777ms | 39ms |
  | 실행 상세 최근 / 180일 전 (구조 변화 포함) | 721 / 673ms | 9 / 7ms |
  | 대시보드 응답시간 변화 (7일) | 221ms | 198ms |
  | 케이스 응답시간 추세 (90일) | 145ms | 140ms |

  - 응답시간 집계(변화·추세·스위트 요약)는 결과를 읽어야 하는 집계라 그대로 두고 200ms 이하 확인. 이력이 더 늘어 느려지면 일 단위 집계 테이블 도입
- 응답 값 추출 (2026-09-27): 케이스의 추출 규칙(`test_case.extractors`, V11)으로 앞 케이스의 응답 값을 같은 스위트 실행의 뒤 케이스 변수로 전달 (`api/README.md` "응답 값 추출"). 1단계 api, 2단계 web(케이스 편집기의 "값 추출", 결과 화면의 추출 표, 스위트 화면의 추출 변수 표시, 복제 시 복사)
  - 위치는 본문 JSON 경로와 응답 헤더 두 가지. 로그인 토큰, 등록 응답의 ID·`Location`이 주 용도
  - 추출 결과는 별도 컬럼 대신 판정 결과(`assertionResults`) 뒤에 덧붙임. 기존 결과 화면·알림·보관 정책을 그대로 사용하고 추출 실패가 판정 실패로 드러남
  - 추출 실패·응답 없음이면 변수를 비움. 환경 변수에 같은 이름이 있을 때 이전 값으로 조용히 전송되는 것 방지
  - 가림 지정 값은 시크릿과 같이 이후 이력에서 값 일치로 가림. 판정 메시지(`was "..."`)도 가리도록 확장 (환경 시크릿 포함)
  - 케이스 단건 실행은 앞 케이스를 함께 실행하지 않음 (의도하지 않은 등록·삭제 요청 방지). 추출 변수가 비면 오류 메시지로 안내
  - 실행 순서는 스위트의 케이스 실행 순서 (아래 "케이스 실행 순서")
- 케이스 실행 순서 (2026-09-27): `test_case.run_order`(V12, 표준 예약어 `POSITION` 대신). 스위트 실행·케이스 목록·응답시간 요약이 같은 순서 (같으면 ID 순)
  - 기존 케이스는 마이그레이션에서 스위트별 ID 순으로 1부터 채움 (이전 동작 유지). 새 케이스는 스위트의 가장 큰 순서 + 1
  - 변경은 스위트 `PATCH`의 `caseOrder`(전체 목록). URL에 동사를 넣지 않는 REST 원칙 유지, 한 번에 전체를 보내 중간 상태 없음
  - 목록이 현재 케이스와 다르면 409. 다른 사람이 그사이 추가·삭제한 케이스를 모르고 덮어쓰는 것 방지
  - 화면은 끌어서 놓기 대신 위·아래 버튼 (키보드·화면 낭독기로 조작, 옮긴 케이스에 초점 유지, 바뀐 위치 안내)
- 변경 이력 (2026-09-27): `change_log`(V13), `GET /api/change-logs` (`api/README.md` "변경 이력"). 1단계 api, 2단계 web(사이드바 "변경 이력" 화면, 항목 "⋯" 메뉴·케이스 수정 화면에서 항목별 이력)
  - 스위트 이력은 스위트와 소속 케이스·스케줄을 함께 표시 (2026-09-28, `change_log.suite_id` V17, `suiteId` 조건). 스위트 결과가 달라진 원인은 대부분 케이스 변경이라서. 기존 기록은 대상의 현재 소속으로 채움 (케이스·스케줄은 스위트를 옮길 수 없어 기록 당시와 같음)
  - 서버 배포 후 여러 사람이 설정을 바꾸므로 "누가 언제 무엇을"을 남김. 실행 순서·값 추출·환경 변수처럼 결과를 바꾸는 설정이 늘어난 것도 이유
  - 값 대신 바뀐 항목 이름만. 변경 전후 값을 남기면 변수·본문에 들어간 민감 정보가 이력에 복제되고, 시크릿은 암호화 값이라 비교 불가
  - 변경과 같은 트랜잭션에서 기록 (`Propagation.MANDATORY`로 트랜잭션 밖 호출 차단). 거부된 변경은 기록 없음
  - 수정 전후 비교는 조회 응답 레코드(`CaseItem` 등)로. 엔티티가 실시간 뷰를 돌려주는 값(환경 변수)은 레코드 생성 시 복사
  - 변경한 사용자는 컨트롤러가 서비스에 명시적으로 전달 (실행 주체 `triggeredBy`와 같은 방식). 서비스가 HTTP 요청에 의존하지 않음
  - 연쇄 삭제는 대상 1건만 기록. 삭제된 하위 항목은 대상 항목 이력에서 "삭제" 없이 끝나지만 프로젝트 조회로 함께 확인 가능
  - 보관기간 없음 (설정 변경은 실행보다 드물어 용량 부담 작음)
- 여기까지 실행 (2026-09-27): 스위트의 처음부터 선택한 케이스까지 실행 순서대로 실행 (`POST /api/runs`의 `throughCaseId`, `test_run.through_case_id` V14)
  - 값 추출로 케이스 사이 의존이 생겨 단건 실행으로는 확인할 수 없는 케이스가 생김. 스위트 전체 실행은 뒤의 등록·삭제 요청까지 보내므로 필요한 앞 케이스만 실행
  - 단건 실행 대상(`test_case_id`)과 별도 컬럼. 두 실행 모두 일부 케이스만 실행하므로 스위트 단위 집계(요약·최근 실행)에서 제외
  - 화면: 케이스 "⋯" 메뉴의 "여기까지 실행", 케이스 수정 화면의 "저장 후 여기까지 실행" (첫 케이스는 단건 실행과 같아 제외). 단건 실행에서 추출 변수가 비면 결과 화면에서 "여기까지 실행" 안내
- 직전 실행 대비 변화 (2026-09-27): 결과 상세에서 같은 스위트·환경의 완료된 직전 스위트 전체 실행과 케이스별 판정 비교 (`api/README.md` "직전 실행 대비 변화")
  - 스케줄 실패 시 "무엇이 새로 깨졌는지"를 바로 보기 위함. 실패 알림의 연속 실패·복구는 스위트 단위라 케이스 단위 변화는 화면에서 확인
  - 조회 시점 계산, 새 테이블 없음. 직전 실행은 `idx_run_suite_time`을 기준 실행의 시작 시각부터 거슬러 1건 (시작 시각 상한을 별도 조건으로 두어야 인덱스 범위 조건이 됨. 45일치 40일 전 실행 기준 29ms → 0.06ms)
  - 단건·여기까지 실행은 일부 케이스만 실행하므로 비교하지 않고, 직전 실행 후보에서도 제외
- 수동 실행 백그라운드 진행 (2026-09-27): `POST /api/runs`는 실행을 만들고 202로 바로 응답, 케이스는 가상 스레드에서 진행. 결과 화면이 1.5초마다 새로 고쳐 진행률(`test_run.planned_count`, V15)과 끝난 케이스 표시
  - 이전에는 실행이 끝날 때까지 요청 하나가 대기. web이 Spring을 부르는 Node fetch의 응답 대기 한도(5분)를 넘는 스위트는 서버에서 계속 실행되어도 화면은 오류
  - 동시 수동 실행 수 제한(기본 4). 실행을 만들기 전에 자리를 잡아 한도 초과 시 실행 없이 429, 준비 실패(404·400) 시 자리 반납
  - 가상 스레드: 실행 대부분이 GW 응답 대기. 케이스별 결과 저장은 짧은 트랜잭션이라 커넥션 풀(5)은 그대로
  - 앱 종료 시 진행 중 실행은 기존 재기동 복구(ABORTED)로 처리. 스케줄 실행은 스케줄러 스레드에서 그대로 동기 진행
  - 테스트: 결과를 바로 검증하는 API 테스트는 요청 스레드에서 진행하는 구현(`InlineBackgroundRunsConfiguration`)으로 교체, 백그라운드 동작은 `ManualRunApiTest`
- 실행 중지 (2026-09-28): `PATCH /api/runs/{id}` `{"status": "CANCELED"}`, 결과 화면 "중지" 버튼. 잘못된 환경으로 시작했거나 앞 케이스가 줄줄이 실패해 나머지가 의미 없을 때, 특히 뒤에 등록·삭제 요청이 있는 스위트를 멈추기 위함
  - 케이스 사이에서 멈춤: 실행 스레드가 다음 케이스를 보내기 전에 상태 확인. 이미 보낸 요청은 되돌릴 수 없으므로 진행 중이던 케이스는 끝까지 기록
  - 중지 요청과 실행 스레드가 같은 실행 행을 바꾸므로 판정 수 증가·완료·중지를 해당 컬럼만 갱신하는 조건부 UPDATE로 처리 (엔티티 전체 저장 시 한쪽이 다른 쪽 변경을 덮어씀). 완료·중지는 `status = RUNNING`일 때만 적용
  - 중지한 사용자는 실행 행(`canceled_by`, V16)에 기록. 변경 이력(설정 변경)에는 넣지 않음
  - 중지된 실행은 일부 케이스만 실행되므로 스위트 요약·최근 실행·직전 실행 비교 대상에서 제외, 실패 알림 없음
  - 중지 뒤 진행 중이던 케이스 결과는 중지 시각부터 40초(케이스 1건 최대 소요: 연결 5초 + 응답 대기 30초 + 여유) 동안 화면 새로 고침으로 반영
- 진행 중인 실행 보기 (2026-09-28): 백그라운드 실행 뒤 다른 화면으로 가도 진행 상황을 확인하도록 대시보드 상단 "진행 중인 실행 N건"(최대 10건, 진행률·주체·결과 링크), 실행 이력 목록은 진행 중인 실행이 보이는 동안 3초마다 새로 고침
  - 기존 실행 목록 API(`status=RUNNING`)에 `plannedCount`만 추가. 새로 고침은 서버 컴포넌트 재실행(`router.refresh`)이라 별도 조회 API 없음
  - 진행 중인 실행이 없으면 새로 고치지 않음 (대시보드 섹션·새로 고침 모두 없음)
- 케이스 검색 (2026-09-28): 사이드바 "케이스 검색"(`/search`), 케이스 목록 API의 `q` (이름·경로 부분 일치, 대소문자 무시, 최대 100건)
  - GW는 경로 접두어로 모듈을 나누므로 경로 일부로 해당 모듈을 검사하는 케이스를 찾는 용도. 케이스 수천 건 규모라 인덱스 없이 LIKE (필요해지면 그때 검토)
  - 화면 주소를 `/cases`가 아닌 `/search`로 둔 이유: `/cases/*`는 "프로젝트" 메뉴 강조 규칙에 포함
  - 태그(교차 조회)는 계획 유지. 검색으로 부족한지 사용해 본 뒤 판단
- 실패 다시 실행 (2026-09-28): 끝난 실행에서 판정 실패·오류가 난 케이스만 같은 환경에서 다시 실행 (`POST /api/runs`의 `rerunOf`, `test_run.rerun_of_run_id` V18). 1단계 api, 2단계 web
  - 고친 뒤 확인할 때마다 스위트 전체를 돌리는 부담을 덜기 위함. 스위트가 클수록, 뒤에 등록·삭제 요청이 있을수록 효과
  - 실패한 케이스가 앞 케이스의 추출 값(로그인 토큰 등)을 쓰면 그 앞 케이스도 함께 실행 (연쇄, 사용자 결정). 원래 실행과 같은 조건이어야 실패를 재현·확인할 수 있어서. 케이스 단건 실행은 대상을 직접 고른 것이라 기존대로 앞 케이스를 실행하지 않음
    - 선정: 실행 순서의 뒤에서부터 거슬러 가며 선택한 케이스가 쓰는 변수(경로·헤더 값·본문의 `{{변수}}`)를 추출하는 가장 가까운 앞 케이스를 선택. 실제 실행에서 바로 앞 추출 값이 쓰이는 것과 같음
    - 함께 실행한 케이스는 결과의 `supporting: true`로 구분 (조회 시 원래 실행의 판정으로 계산, 저장하지 않음)
  - 환경은 원래 실행의 환경 (요청에 지정 불가). 대상 케이스는 현재 정의로 실행하고 원래 실행 뒤 삭제된 케이스는 제외
  - 다시 실행도 다시 실행 가능 (남은 실패만 반복 확인). 진행 중인 실행·실패 없는 실행은 409
  - 일부 케이스만 실행하므로 단건·여기까지 실행과 같이 스위트 요약·최근 실행·직전 실행 비교에서 제외 (`TestRun#isWholeSuite`)
  - 화면: 끝난 실행에 실패·오류가 있으면 결과 화면 머리의 주 버튼 "실패만 다시 실행" (진행 중에는 "중지"). 확인 창 없이 바로 시작 ("지금 실행"과 같음)
    - 다시 실행 결과는 제목의 "실패 다시 실행" 배지, "원래 실행 #N" 링크, 함께 실행한 케이스 행의 "함께 실행" 배지로 구분. 실행 이력·대시보드 진행 중 목록에 "#N 실패 다시 실행"
    - 버튼 이름에 건수를 넣지 않음: 바로 옆 판정 수 배지("실패 1")와 이름이 겹쳐 화면 낭독기·자동화 검사에서 구분이 어려움
- 점검 시간 (2026-09-29): 환경별로 기간을 등록하면 그동안 그 환경의 스케줄 실행을 건너뜀 (`maintenance_window` V19, `/api/maintenance-windows`, `api/README.md` "점검 시간"). 1단계 api, 2단계 web
  - 서버 배포 후 GW 배포·점검 때마다 예상된 실패 알림이 채널에 쏟아지는 것 방지. 스케줄을 끄고 다시 켜는 방식은 다시 켜는 것을 잊으면 모니터링이 조용히 멈춤
  - 실행을 건너뜀 (사용자 결정). 실행하고 알림만 끄면 점검 중 실패가 스위트 요약·직전 실행 비교·연속 실패 계산에 섞여 모두 따로 제외해야 함. 건너뛰면 기록이 없어 손댈 곳이 스케줄러 한 곳. 점검 중 상태는 수동 실행으로 확인
    - 점검 뒤 첫 실행이 통과해도 복구 알림 없음 (직전 스케줄 실행이 점검 전의 정상 실행)
  - 기간 최대 7일. 끝 시각이 지나면 따로 조치 없이 다시 실행. 취소·조기 종료는 삭제(보관)
  - 건너뛴 스케줄은 다음 실행 시각만 옮기고 마지막 실행 시각은 그대로 (스위트 화면의 "마지막 실행"이 실행하지 않은 시각을 가리키지 않도록)
  - 등록·변경·삭제는 변경 이력에 기록 (누가 알림을 멈췄는지). 삭제된 환경의 점검은 목록에서 제외 (연쇄 보관 대신 조회 조건)
  - 화면: 프로젝트 화면 환경 "⋯" 메뉴의 "점검 시간 등록"(기본 지금부터 1시간), 환경 이름 옆 점검 중·예정 배지, 점검이 있을 때만 "점검 시간" 표(수정, 진행 중은 "지금 종료", 예정은 "취소"). 대시보드 상단에 점검 중인 환경
    - 입력·표시는 화면의 다른 시각과 같이 KST (`datetime-local` 값에 +09:00, 한국은 일광 절약 시간 없음). 서버와 같은 기준(끝이 시작 뒤, 끝이 지나지 않음, 최대 7일)을 먼저 확인해 한국어로 안내
    - 종료·취소는 삭제와 같은 동작이지만 확인 버튼 이름을 "지금 종료"·"점검 취소"로 (삭제라는 말이 결과를 잘못 전달)
- 전송 오류 재시도 (2026-09-29): 응답을 받지 못한 케이스를 잠시(기본 1초) 뒤 다시 보냄, 기본 1회 (`api/README.md` "전송 오류 재시도", `test_result.retry_count` V20). 1단계 api, 2단계 web
  - 스케줄 실행이 잦을수록 GW·네트워크의 짧은 흔들림만으로 실패 알림이 감. 점검 시간이 예상된 실패를 막는다면 재시도는 우연한 실패를 줄임
  - 범위 (사용자 결정): 연결 실패는 모든 메서드(서버에 닿지 않음), 타임아웃·연결 끊김은 조회 메서드만(서버가 처리했을 수 있어 등록이 두 번 되는 것 방지), 판정 실패는 재시도 없음
  - HTTP 클라이언트의 자동 재시도는 계속 끔. 첫 응답이 가려지고 기록이 남지 않으므로, 앱이 재시도하고 횟수를 결과에 기록 (잦으면 그 자체가 불안정 신호)
  - 재시도 정책은 스프링 의존성 없는 `runner.TransportRetry` (대기는 주입, 테스트에서 대기 없이 확인). 수동 실행은 가상 스레드, 스케줄 실행은 스케줄러 스레드에서 대기
  - 컬럼 추가는 상수 기본값이라 PostgreSQL 11 이상에서 행을 다시 쓰지 않음. 45일치 결과 129만 건에서 1ms (행 수와 무관, `scripts/perf`)
  - 화면: 결과 화면 케이스 행의 "재시도 N회" 배지(0회는 표시 없음). 펼치면 재시도 뒤 받은 응답이면 그 사실과 소요 시간 기준(마지막 전송), 끝내 응답이 없었으면 오류 상자 안에 다시 보낸 횟수
- 프로젝트별 알림 채널 (2026-09-30): 프로젝트에 Webhook을 지정하면 그 프로젝트의 스케줄 실행 실패·복구 알림을 그 채널로 (`api/README.md` "프로젝트별 채널", `project.webhook_url_cipher` V21). 1단계 api, 2단계 web
  - 서버 배포 후 GW·모듈마다 보는 사람이 다름. 모든 알림이 한 채널에 섞이면 자기 것이 아닌 알림을 무시하게 되고 중요한 알림도 놓침
  - 주소는 자격증명: 환경 시크릿과 같은 키로 암호화 저장, 응답·변경 이력·로그에 주소 없음 (지정 여부만)
  - 서버 기본 Webhook은 운영자가 환경변수로 넣지만 프로젝트 Webhook은 로그인한 사용자 누구나 지정. 서버가 내부 주소로 요청을 보내는 통로가 되지 않도록 실행기의 목적지 확인(링크 로컬·클라우드 메타데이터 차단, 리다이렉트 미추적)을 그대로 사용
  - 연결 확인 알림(사용자 결정): 등록 직후 주소 오타·권한 문제를 발견. 실제 실패가 나야 확인되는 일 방지
  - 주소를 읽을 수 없으면 기본 채널로 (알림 유실보다 다른 채널로 가는 편이 나음). 스케줄러 자체 알림(DB 연결 등)은 프로젝트와 무관하여 기본 채널
  - 화면: 프로젝트 화면 맨 아래 "알림 채널" (환경·스위트보다 드물게 바꾸는 설정). 지정·변경은 대화상자, 삭제는 "기본 채널로 되돌리기"(확인 창), 지정되어 있으면 "테스트 알림 보내기"와 결과 문구
    - 저장한 주소는 다시 표시하지 않으므로 변경 창은 빈 칸에서 새로 입력
- 불안정 케이스 (2026-09-30): 기간 안 스케줄 실행에서 실패 구간 2회 이상 또는 재시도한 실행 2회 이상인 케이스·환경 (`GET /api/unstable-cases`, `api/README.md` "불안정 케이스"). 1단계 api, 2단계 web
  - 알림이 왔다 복구되기를 반복하는 케이스는 알림 전체의 신뢰를 떨어뜨림. 모아 보고 판정 기준을 고칠지 GW 문제로 넘길지 판단
  - 기준 (사용자 결정): 실패 횟수가 아니라 실패 구간. 한 번의 장애(실패가 이어지다 복구)와 간헐 실패를 구분. 재시도로 통과해 가려진 흔들림도 포함
  - 쿼리 선택 (1년치 결과 1천만 건, 실행의 34%에 실패가 있는 최악 분포, 30일):
    | 방식 | 시간 | 비고 |
    |------|-----:|------|
    | 기간 결과 전체에 창 함수(`LAG`) | 1,205ms | 결과 테이블을 거의 다 읽음 |
    | 실패 실행만 조인 | 848ms | 창 함수 뒤 조건은 행 수를 추정하지 못해 결과 테이블 전체 읽기를 고름. 이력이 늘수록 느려짐 |
    | 실패·재시도 실행마다 `LATERAL` + `OFFSET 0 ROWS` 경계 | 362ms | 기간 안 실행 수에만 비례. 실제에 가까운 분포(실행의 0.5%에 실패)에서 7일 39ms |
  - 재시도한 실행을 결과 테이블 없이 고르려고 실행 행에 재시도 수(`test_run.retried_count`, V22). 판정 수(V10)와 같은 방식
  - 화면: 대시보드 맨 아래 "불안정 케이스"(대시보드 기간, 많은 순 최대 10건, 누르면 그 스위트·환경의 실행 이력에서 실패·복구 흐름 확인), 스위트 화면 케이스 이름 옆 "불안정" 배지(최근 7일, 환경별 이유)
    - 스케줄 실행으로만 판단하므로 E2E에서 데이터를 만들 수 없음 (cron 최소 1분 간격). 표는 컴포넌트 단위 테스트, 스위트 화면 배지는 일회용 E2E DB에 스케줄 실행을 넣어 화면으로 확인
- CI 연동 (2026-09-30): 배포 파이프라인이 스위트 실행을 시작하고 결과로 배포 단계를 통과·실패시킴. 1단계 api(API 토큰, `access_token` V23), 2단계 web(토큰 관리 화면)과 실행 대기 스크립트·CI 예시 (`api/README.md` "API 토큰")
  - 스케줄 실행(최대 한 주기 뒤)이나 사람이 누른 실행보다 먼저, 방금 배포가 무엇을 깨뜨렸는지 배포 단계에서 확인
  - 로그인 세션(12시간 고정 만료)은 자동화에 맞지 않아 별도 토큰. 세션과 같이 무작위 256비트를 SHA-256 해시로 저장, 원문은 발급 응답에만
  - 권한 (사용자 결정): 실행 시작·그 결과 조회만. CI 비밀 값은 여러 사람·도구가 다루므로 새도 설정 변경·환경 변수 조회·사용자 관리는 불가하도록
  - 만료 (사용자 결정): 발급 때 30·90·180·365일 중 선택, 만료 없는 토큰 없음 (잊힌 토큰이 계속 유효하지 않도록)
  - 토큰 확인은 `snt_` 접두어가 있을 때만 (세션 요청마다 토큰 조회를 하지 않음). 허용 경로는 숫자 ID만 받는 정규식으로 (다른 경로 표기로 우회 불가)
  - 네트워크 경로 (사용자 결정): Spring은 같은 PC에서만, web BFF는 조회·쿠키 세션만이라 다른 PC의 CI가 닿을 수 없었음. HTTPS 앞단(Caddy)이 `Bearer snt_` 헤더가 있는 `/api/runs` 요청만 Spring으로 직접 전달. web에 CI 전용 전달 경로를 두는 방안은 "쓰기는 Server Action으로만" 원칙의 예외가 생겨 제외
  - 실행 대기 스크립트 `scripts/sentinel-run.sh` (bash·curl·jq): 종료 코드 0 통과, 1 실패, 2 실행 불가·중지·시간 초과. CI 도구와 무관하게 쓰도록 스크립트로 제공하고 GitHub Actions는 예시만
  - 화면: 계정 영역의 "API 토큰" (발급 직후 한 번만 표시·폐기·마지막 사용 시각). 프로젝트 화면 환경 이름 옆에 ID 표시 (스크립트 인자)
  - JUnit XML (2026-10-01): `GET /api/runs/{id}`의 `Accept: application/xml` 응답, 스크립트 `--junit`. 배포 단계 화면에서 어떤 케이스가 왜 실패했는지 CI 도구가 표로 보여 줌. 새 주소 대신 같은 주소의 다른 형식이라 토큰 권한·HTTPS 앞단 경로를 바꾸지 않음. 원문은 넣지 않음 (CI 로그·산출물은 볼 수 있는 사람이 더 많음)
- 모니터링 공백 (2026-10-02): 스케줄이 예정 시각보다 10분(`sentinel.schedule.gap-threshold`) 넘게 늦게 실행되면 그 기간을 기록·알림 (`monitoring_gap` V24, `GET /api/monitoring-gaps`, `api/README.md` "모니터링 공백"). 1단계 api, 2단계 web
  - Sentinel이 멈추면 실패 알림도 멈춰 "알림 없음"이 정상처럼 보임. 노트북·단일 서버 운영이라 절전·재부팅이 잦음
  - 저장·화면 표시 (사용자 결정): 알림만으로는 지난 기간의 결과를 볼 때 공백을 알 수 없어, 대시보드 기간의 공백을 함께 표시
  - 감지는 스케줄의 늦음으로 판단 (폴링 자체의 심장 박동 기록 대신). 확인할 스케줄이 없던 시간은 놓친 모니터링이 아니고, 매 주기 쓰기가 없음. 멈춘 동안은 알 수 없어 재개 후 알림 (외부 감시는 별도 과제)
  - 기준 10분: 폴링 주기(60초)와 평소 스위트 실행 시간보다 충분히 길게. 재배포(1~2분)는 공백 아님
  - 끝난 뒤 기준 시간 안에 시작된 공백은 합침: 긴 공백 뒤 밀린 실행 처리로 다음 폴링이 늦어져 알림이 두 번 가는 일 방지
  - DB 연결 복구 알림과 같은 기간이면 공백 알림 생략 (기록은 함)
  - 화면: 대시보드 요약 수치 위 "모니터링 공백"(대시보드 기간과 겹치는 공백, 있을 때만). 요약 수치를 읽기 전에 빠진 기간이 있음을 알림. 건수·합계 길이, 최근 5건
    - 폴링이 10분 넘게 멈춰야 생기므로 E2E에서 만들 수 없음. 목록은 컴포넌트 단위 테스트, 대시보드 배치·접근성은 일회용 E2E DB에 공백을 넣어 화면으로 확인
- 실패 원인 분류 (2026-10-04): 기간 안 스케줄 실행의 실패·오류를 7가지 원인(타임아웃·연결 실패·연결 끊김 / 5xx·4xx·응답 내용 불일치 / 설정 오류)으로 집계 (`GET /api/failure-causes`, `api/README.md` "실패 원인"). 1단계 api, 2단계 web(대시보드)
  - 알림이 왔을 때 우리 API 문제인지 GW·네트워크 문제인지를 먼저 가르기 위함. 원인 묶음(NETWORK·API·SETUP)이 곧 먼저 볼 곳
  - 분류·대상 (사용자 결정): 7가지, 스케줄 실행만. 내부 오류(`UNEXPECTED`)는 7가지 어디에도 맞지 않아 있을 때만 "기타"
  - 기존 기록(오류 사유·상태 코드)만으로 SQL에서 분류. 응답 시간 초과를 따로 나누려면 판정 결과 JSON을 읽어야 해 제외 (응답 내용 불일치에 포함)
  - 성능: 불안정 케이스와 같은 `LATERAL` + `OFFSET 0 ROWS`. 45일치 7일 29ms, 30일 200ms (기간 안 실행 수에 비례하므로 1년치에서도 같은 기간이면 비슷)
  - 주간 요약 (2026-10-04): "실패 원인: 서버 오류 5xx 74%, 타임아웃 20%" 한 줄. 대시보드를 열지 않는 사용자도 그 주 실패가 어디서 왔는지 알림에서 확인. 원인 이름은 화면과 같은 이름(`Cause.label`)
- 휴일 건너뛰기 (2026-10-04): 등록한 휴일에는 휴일 건너뛰기를 켠 스케줄 실행을 건너뜀 (`holiday` V28, `test_schedule.skip_holidays`, `/api/holidays`, `api/README.md` "휴일"). 1단계 api, 2단계 web
  - 평일 cron(`1-5`)은 공휴일을 거르지 못해, 대상 시스템도 쉬는 날 실패 알림·요약 통과율 하락이 생김. 매번 스케줄을 끄고 켜는 방식은 다시 켜는 것을 잊으면 감시가 멈춤
  - 적용 범위 (사용자 결정): 스케줄마다 선택, 기본 꺼짐. 24시간 감시 스케줄은 휴일에도 실행해야 하므로 전체 일괄 적용 대신
  - 휴일 목록 (사용자 결정): 화면에서 직접 등록. 공휴일 API 연동은 외부 키·내부망에서의 외부 접근이 필요하고 자체 휴일은 어차피 직접 등록. 휴일은 전체 공용 (프로젝트별 아님)
  - 동작은 점검 시간과 같음: 다음 실행 시각만 옮김 (실행 기록·알림·모니터링 공백 없음, 손댈 곳이 스케줄러 한 곳). 날짜는 `sentinel.schedule.zone` 기준
  - 모든 스케줄에 영향을 주므로 등록·삭제는 관리자만, 변경 이력에 기록
  - 화면 (2026-10-05): 사이드바 "휴일"(`/holidays`, 연도별 목록·오늘·다음 휴일), 스케줄 대화상자의 "휴일 건너뛰기" 체크와 스케줄 표의 "휴일 제외" 배지
    - 조회는 모든 사용자: 스케줄이 왜 실행되지 않았는지 구성원도 확인할 수 있도록. 추가·삭제 버튼은 관리자에게만 표시 (사용자 관리처럼 화면 자체를 막지 않음)
    - 날짜 입력은 `date`(KST 날짜 그대로 전송, 시간대 변환 없음). 요일은 날짜 문자열로 계산해 서버·브라우저 시간대와 무관
  - 한꺼번에 등록 (2026-10-05): 1년치 공휴일(20건 안팎)을 하나씩 넣는 부담을 덜기 위함 (`POST /api/holiday-imports`). 1단계 api, 2단계 web
    - 입력 방식 (사용자 결정): 여러 줄 붙여넣기 ("2026-12-25 성탄절"). 줄 해석은 web, api는 날짜·이름 목록만 받음
    - 평일 스케줄 일괄 켜기는 제외 (사용자 결정): 24시간 감시 스케줄을 실수로 켜는 일 방지
    - 이미 등록한 날짜는 오류 대신 건너뜀: 해마다 같은 목록을 다시 붙여 넣어도 안전. 그 외 잘못된 줄은 전부 미등록 (일부만 들어가 무엇이 빠졌는지 헷갈리지 않도록)
    - 화면 (2026-10-05): 휴일 화면 "여러 건 붙여넣기"(관리자). 날짜는 `-`·`.`·`/` 구분과 한 자리 월·일, 날짜 뒤 요일 "(금)" 허용 (달력·웹 문서에서 복사한 형태). 입력하는 동안 읽은 건수나 첫 잘못된 줄 번호를 표시해 등록 전에 고침. 등록 후 건너뛴 휴일은 기존 이름으로 표시
- 스케줄 한눈에 보기·일괄 변경 (2026-10-07): 여러 스케줄의 활성·휴일 건너뛰기를 한 번에 (`PATCH /api/schedules`). 1단계 api, 2단계 web(전체 스케줄 화면)
  - 스케줄이 스위트 화면마다 흩어져 있어 전체 실행 시각·꺼진 스케줄을 한눈에 볼 수 없고, 휴일 건너뛰기도 하나씩 켜야 했음
  - 컬렉션에 대한 부분 수정(`PATCH /api/schedules`)으로 동사 없는 주소 유지. 한 트랜잭션, 하나라도 없으면 전부 미변경
  - 이미 켜진 스케줄은 다시 켜지 않음: 다음 실행 시각이 다시 계산되면 곧 돌 실행을 건너뜀
  - 화면 (2026-10-07): 사이드바 "스케줄"(`/schedules`). 다음 실행 순(꺼진 스케줄은 뒤), 프로젝트·상태 필터, 골라서 켜기·끄기·휴일 건너뛰기
    - 끄기만 확인 창: 감시가 멈추고 실패 알림도 오지 않는 동작. 켜기·휴일 건너뛰기는 되돌리기 쉬워 바로
    - 이름은 스케줄 응답에 없어 화면에서 프로젝트·스위트·환경 목록과 이어 붙임 (실행 이력 화면과 같은 방식). 등록·cron 수정은 스위트 화면에 두어 한 곳에서만
- 시스템 상태 (2026-10-10): 관리자가 서버 설정·버전 상태를 한곳에서 확인 (`GET /api/system-status`, `api/README.md` "시스템 상태"). 1단계 api, 2단계 web
  - 배포·업데이트·키 교체 뒤 확인을 서버 로그와 preflight에 기대고 있어 서버 배포 확인 절차를 화면으로 옮기기 위함
  - 설정은 지정 여부만, 주소·키 값은 응답에 없음 (자격증명). 관리자만: 서버 구성 정보이므로
  - 버전은 Gradle 빌드 정보(버전·빌드 시각). 이미지 빌드 문맥(`api/`)에 `.git`이 없어 커밋은 제외. 소스가 같으면 이미지 캐시로 빌드 시각도 그대로라 업데이트 반영 확인에 사용
  - 풀 수 없는 시크릿은 조회할 때마다 다시 확인 (기동 시 결과를 저장하지 않음): 화면에서 다시 입력하면 바로 사라지도록. 사용 중인 항목만 (보관된 항목은 화면에서 고칠 수 없음)
  - 화면 (2026-10-10): 사이드바 관리자 메뉴 "시스템 상태"(`/system`). 버전, 암호화 키(상태·할 일·풀 수 없는 값과 프로젝트 링크), 알림·감시 설정(설정 여부와 없을 때 영향), 보관 기간
    - 대시보드의 기본 채널 주소 미설정 안내는 유지: 구성원도 알림이 로그로만 남는 이유를 알아야 하므로. 시스템 상태는 관리자의 배포 확인용
- 알림 채널 서식 (2026-10-04): Webhook 주소로 Slack(Block Kit)·Teams(Adaptive Card)를 판별해 채널 서식으로, 그 외는 예전 텍스트 (`api/README.md` "발송 채널"). api 1단계
  - 알림 종류가 늘며 모두 텍스트 한 덩어리라 제목·본문 구분이 없고 링크가 긴 주소 그대로였음. 사용자가 매일 가장 많이 보는 화면
  - 채널 (사용자 결정): 둘 다, 주소로 자동 판별. 프로젝트마다 다른 채널을 써도 설정 없음. Teams의 새 워크플로 Webhook은 카드 형식이 필요
  - 알림에 성격(실패·악화·복구·요약)과 링크(결과·대시보드)를 본문 줄과 분리. 텍스트 서식은 예전과 같은 결과("결과: 주소" 줄)
  - 판별은 주소의 호스트로만 (`hooks.slack.com` 정확히, Teams는 알려진 도메인으로 끝남). 비슷한 이름의 다른 주소·자체 중계 서버는 텍스트
  - Slack 본문은 plain_text: 경로·JsonPath의 `_`·`*`가 서식으로 바뀌지 않도록
- 응답시간 악화 알림 (2026-10-04): 매시 케이스·환경별 최근 24시간 p95를 그 전 7일과 비교해 50% 이상이면서 100ms 이상 느리면 1회, 회복 1회 (`slowdown_alert` V27, `sentinel.slowdown`, `api/README.md` "응답시간 악화 알림"). api 1단계
  - 판정 규칙의 시간 한도를 넘기 전에는 실패로 잡히지 않아, 배포 뒤 p95가 두 배가 되어도 주간 요약에서야 보였음
  - 기준·구간·반복 (사용자 결정): 50% 이상이면서 100ms 이상, 최근 24시간 vs 그 전 7일, 악화 1회·회복 1회
  - 회복 기준은 악화 기준의 절반: 기준선에서 오르내리며 악화·회복 알림이 번갈아 오지 않도록
  - 회복은 감지 당시 비교 p95 기준으로 저장: 비교 기간(7일)이 지나며 느린 값을 흡수하면 그대로 두어도 "회복"이 되어 버림
  - 집계는 대시보드 응답시간 변화와 같은 쿼리(8일치, 1년치 데이터에서 14일 198ms). 매시 1회라 부담 적음
  - 화면 표시 (2026-10-04): 열린 알림 조회 `GET /api/slowdowns` (1단계 api), 대시보드 "지금 느린 케이스"·스위트 화면 "느림" 배지 (2단계 web). 알림과 화면이 같은 상태를 가리키도록. 대시보드의 "응답시간이 나빠진 케이스"는 기간 비교 순위로 유지
- 외부 생존 신호 (2026-10-03): 스케줄 폴링마다 DB 조회가 성공하면 외부 감시 서비스 주소로 GET (`sentinel.heartbeat.url`, `api/README.md` "외부 생존 신호"). api 1단계
  - 모니터링 공백 알림은 재개 후에야 감. 노트북이 꺼지거나 프로세스가 죽은 그 순간은 Sentinel 스스로 알릴 수 없어 외부에서 감시
  - 형식은 GET 한 가지 (healthchecks.io·Uptime Kuma·Better Stack 공통). 실패 신호(`/fail`)처럼 서비스마다 다른 규칙은 쓰지 않음
  - 실행 전에 보냄: 실행 뒤에 보내면 긴 스위트 동안 신호가 끊겨 오탐. 폴링이 아예 멈추면(스레드 정지) 신호도 멈추도록 별도 스레드가 아니라 폴링 안에서
  - 주소는 자격증명. RestClient 예외 메시지에 요청 주소가 들어 있어 상태 코드·실패 종류만 로그. 같은 문제로 기본 채널 Webhook 발송 실패 로그에 주소가 남던 것도 함께 수정
- 주간 요약 알림 (2026-10-02): 매주 월요일 09:00 지난주 실행 수·통과율(지난주 대비)·통과율 낮은 스위트·불안정 케이스·응답시간 악화·모니터링 공백을 기본 채널로 1건 (`sentinel.digest`, `digest_delivery` V25, `api/README.md` "주간 요약"). api 1단계
  - 이벤트 알림(실패·복구·공백)만으로는 한 주의 흐름을 대시보드를 열어야 알 수 있고, 대시보드를 열지 않는 사용자에게는 전달되지 않음
  - 채널·시각 (사용자 결정): 기본 채널에 전체 1건 (프로젝트가 적은 지금 규모), 월요일 09:00 (한 주를 시작할 때 지난주 확인). 시각은 설정으로 변경
  - 값은 대시보드와 같은 서비스로 계산 (기준이 달라 숫자가 어긋나지 않도록)
  - 발송은 cron 작업 대신 폴링 주기마다 예정 시각 확인: cron 작업은 그 시각에 꺼져 있으면 건너뛰므로, 스케줄과 같이 켜진 뒤 1회 발송. 예정 시각마다 기록을 남겨 재시작해도 중복 없음 (같은 시각 기록은 유일 제약)
  - 설치 전에 끝난 기간은 건너뜀 (2026-10-11): 새 서버 리허설에서 설치 직후 지난주(설치 전) 빈 요약이 기본 채널로 감. 설치일은 첫 마이그레이션 적용일. 실행 0건만으로 건너뛰지 않음: 설치 뒤 실행이 없는 주는 "스케줄이 꺼졌거나 멈췄는지 확인" 신호라 보내야 함
  - 기록을 먼저 남기고 발송: 발송 실패 시 재시도하지 않음. 실패한 Webhook에 매 주기 같은 요약을 다시 보내는 것보다 1회 누락이 나음 (실패는 ERROR 로그)
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
- ~~실패 알림 채널 (메신저 Webhook 규격)~~ 해결 (2026-10-04): Slack·Teams는 주소로 판별해 채널 서식, 그 외는 텍스트 ("알림 채널 서식")
- 개발환경 야간 가동 여부
- RDS 파라미터 그룹의 타임아웃 설정값
