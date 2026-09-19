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
- Next.js → Spring: Route Handler에서 서버사이드 호출, CORS 미발생
- Spring → GW: 환경별로 저장된 시스템 자격증명을 Spring이 주입. 사용자 토큰 패스스루 금지

### 인증 범위
- 회원가입, 비밀번호 찾기, 이메일 인증, 약관 동의 전부 불필요
- SSO 존재 시 SSO만 연동, 없으면 관리자 직접 등록
- 역할 구분 초기 미도입, 필요 시 ADMIN 추가
- 인증을 남기는 이유는 접근 통제가 아니라 저장된 운영 토큰 보호 및 실행 주체 기록

### 보안 유의사항
- 임의 URL 실행이 가능한 구조이므로 SSRF 위험 존재.  
  - Spring Boot 4.1의 HTTP 클라이언트 SSRF 완화 기능 우선 검토 후 부족분만 화이트리스트로 보완
- 환경 단위 허용 호스트 화이트리스트. GW가 하나이므로 호스트 1개 등록으로 해결
- 환경별 토큰·API 키 평문 저장 금지 (pgcrypto 또는 애플리케이션 레벨 암호화)
- Spring 주소는 서버 전용 환경변수로 관리. NEXT_PUBLIC_ 접두어 사용 시 브라우저 번들에 내부 주소 노출

---

## 3. 기술 스택

| 구분 | 선택 |
|------|------|
| Java | 25 |
| BE | Spring Boot 4.1 (Spring Framework 7.0.8 기반) |
| ORM | JPA (heavy query 부재, 단순 스키마) |
| DB | PostgreSQL — 개발기 AWS RDS의 sentinel 스키마 |
| 실행기 | RestClient |
| 스케줄 | DB 폴링 (5장 참조) |
| 스키마 관리 | Flyway + ddl-auto: validate (2026-09-19 배포 구성 시 전환) |
| FE | Next.js 16.3.4 (Active LTS), App Router, TanStack Query |
| 실행 환경 | 노트북 상시 실행, Docker 컨테이너 2개 |

### Spring Boot 4 관련 유의사항
- spring-boot-autoconfigure가 목적별 소형 모듈로 분리됨. 기존 3.x 예제의 의존성을 그대로 복사하면 실패. start.spring.io에서 생성 권장
- Jackson 3.x로 변경, 패키지 경로가 tools.jackson으로 이동
- @Retryable이 프레임워크에 포함 (재시도 횟수·지연·지터·백오프 설정 가능)
- @ConcurrencyLimit으로 동시 호출 수 제한 가능. 스위트 실행 시 GW 동시 호출 제어에 활용
- @Async 비동기 컨텍스트 전파 지원. 스케줄 실행 로그 추적에 유리
- 스케줄러는 DB 폴링으로 확정하여 Quartz 스타터 미사용

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
- test_case.assertions jsonb 단일 컬럼 확정 (2026-09-19). 규칙 종류 추가 시 스키마 변경 불필요, 케이스 조회 시 함께 로딩
- `type` 속성으로 종류 구분하는 다형 JSON (statusCode, responseTime, header, jsonPath, bodyContains)
- 규칙 미지정 시 기본 판정 `status in [2xx]`. 미적용 시 5xx 응답도 통과 처리되는 문제 방지
- 결과는 test_result.verdict(PASSED/FAILED/ERROR) + assertion_results jsonb(규칙별 설명·통과 여부·실제 값)
- 판정 단위 SQL 조회(예: 응답 시간 규칙 보유 케이스 검색)는 불편. 현재 요구사항에 없음

### 엔티티 유의사항
- TestRun에 TestResult 양방향 @OneToMany 금지. 단방향 @ManyToOne만 두고 리포지토리 조회
- 외래키 제약 미사용 (2026-09-19, V2에서 제거). 참조 무결성은 애플리케이션에서 보장
  - 엔티티 `@JoinColumn(foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))`로 DDL 생성 시에도 FK 미생성
  - 조회용 인덱스와 유니크 제약은 유지
  - SQL 직접 입력 시 존재하지 않는 ID 참조를 DB가 막지 않으므로 ID 하드코딩 대신 INSERT ... RETURNING 참조 사용 (`docs/sample-cases.sql` 방식)
  - 삭제 시 하위 데이터(결과·실행·스케줄) 처리는 애플리케이션 책임. 삭제 정책(소프트 삭제 등)은 FE 단계에서 결정
- responseBody는 저장 전 크기 제한 (예: 64KB) 후 truncate
- 인덱스는 데이터 축적 전 선반영

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
- 채널 미정으로 `Notifier` 인터페이스 분리, 임시 구현 `LoggingNotifier`(WARN 로그). 채널 확정 시 구현체 추가 후 `NotificationConfig` 빈 교체
- 발송 대상: 스케줄 실행 결과의 판정 실패·오류, 스케줄 실행 자체 오류, DB 연결 실패·복구. 수동 실행은 화면·API 응답으로 확인하므로 미발송
- 실패 사유는 실패한 판정 규칙만 표시(`status in [2xx]: was 500`), 최대 20줄 후 `… 외 N건`
- 알림 발송 실패는 로그만 기록하고 폴링 계속

---

## 6. 실행 환경

### 컨테이너 구성
```
sentinel-api   :8080   Spring Boot
sentinel-web   :3000   Next.js (standalone)
```

루트 `docker-compose.yml`로 실행 (2026-09-19 구성, 현재 api만. web은 4단계에서 추가)

```bash
docker compose up -d --build
```

- 이미지: `api/Dockerfile` 멀티 스테이지 (eclipse-temurin 25 JDK 빌드 → 25 JRE 실행, 비root 사용자)
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

- 응답 원문은 최근 30일 보관, 스키마 지문(jsonb)은 영구 보관
- 지문은 값이 아닌 구조만 평탄화하여 저장. 비교가 집합 연산으로 단순화
- 배열은 전 요소 타입 유니온 병합 후 []로 통일
- null은 관측 누적으로 승격만 허용, 강등 금지
- 동적 키는 형제 키 임계치 초과 시 *로 축약
- 필수/선택은 present/observed 비율로 판정
- 드리프트 등급: BREAKING(필수 필드 소멸, 타입 변경) / WARNING(nullable 전환) / INFO(신규 필드)
- baseline 갱신은 INFO만 자동, BREAKING·WARNING은 승인 후 반영

### 기타
- OpenAPI / Postman 컬렉션 임포트를 통한 케이스 스켈레톤 자동 생성
- 일 단위 집계 테이블(p50/p95) 기반 응답시간 추세 조회
- 결과 데이터 보관기간 파기 배치

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
