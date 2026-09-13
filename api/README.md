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
| DB | PostgreSQL, 개발기 AWS RDS `sentinel` 스키마 (서버 버전 미확인) |

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

1. `api` 폴더를 프로젝트로 열기. `build.gradle` 자동 인식
2. **Settings → Build, Execution, Deployment → Build Tools → Gradle**
   - Gradle JVM: JDK 25
3. **File → Project Structure → Project**
   - SDK: JDK 25, Language level: 25
4. **Run → Edit Configurations → SentinelApplication**
   - JDK 25, 클래스패스 모듈 `sentinel.main`
   - Main class: `com.sentinel.SentinelApplication`
   - Environment variables: `../sentinel.env` 불러오기 또는 직접 입력

## 빌드 및 테스트

```bash
./gradlew build     # 컴파일 + 테스트 + jar 생성
./gradlew test      # 테스트만 실행
```

`SentinelApplicationTests`는 스프링 컨텍스트 전체 기동 테스트이므로 테스트 실행 시에도 DB 환경변수 필요

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
