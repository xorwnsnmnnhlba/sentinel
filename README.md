# Sentinel

API 검증의 정시 자동 실행 및 결과 이력 공유 도구

> LLM 기반 에이전트가 아닌, 등록된 테스트 케이스의 실행·판정 자동화 러너

## 구조

```
[브라우저] → Next.js (:3000, BFF) → Spring Boot (:8080) → GW → 내부 모듈
                                          ↓
                              AWS RDS (sentinel 스키마)
```

```
sentinel/
 ├─ api/          Spring Boot 백엔드 (러너, 스케줄러, API)
 ├─ web/          Next.js 프론트엔드 (BFF, 화면)
 ├─ docs/         설계 문서
 └─ sentinel.env  로컬 시크릿 (git 제외, 직접 생성)
```

## 스택

| 모듈 | 구분 | 버전 | 문서 |
|------|------|------|------|
| `api` | Java | 25 (로컬 Azul Zulu 25.0.4.1) | [api/README.md](api/README.md) |
| | Spring Boot | 4.1.0 (Spring Framework 7.0.8) | |
| | Hibernate ORM | 7.4.1.Final | |
| | PostgreSQL JDBC | 42.7.11 | |
| | Gradle | 9.7.1 | |
| `web` | Next.js | 16.3.5 | [web/README.md](web/README.md) |
| | React | 19.2.8 | |
| | TypeScript | 5.9.3 | |
| | Tailwind CSS | 4.3.3 | |
| | Node.js | 20.9.0 이상 | |

모듈별 전체 의존성 버전은 각 README 참고

## 빠른 시작

### 1. 사전 준비
- JDK 25
- Node.js 20.9.0 이상
- 개발기 RDS 접속 환경 (VPN 또는 보안 그룹 IP 허용)

### 2. 환경변수 파일 작성
루트에 `sentinel.env` 생성 후 값 입력. `.gitignore` 등록 완료

```
DB_URL=jdbc:postgresql://<rds-endpoint>:5432/<db>?currentSchema=sentinel
DB_USERNAME=
DB_PASSWORD=
```

값에 따옴표 사용 금지. `docker --env-file`은 따옴표까지 값으로 인식

### 3. 실행

```bash
# api (:8080)
cd api
set -a; source ../sentinel.env; set +a
./gradlew bootRun
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
