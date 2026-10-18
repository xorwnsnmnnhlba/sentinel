# 브랜치 규칙

최종 갱신: 2026-09-13

---

## 1. 전략

GitHub Flow. `main` 하나와 단기 작업 브랜치로 운영

- 1인 개발, 단일 레포(api + web) 규모에서 develop·release 브랜치를 두는 Git Flow는 관리 비용만 발생
- `main`은 노트북 상시 실행 대상이므로 항상 실행 가능한 상태 유지

```
main ──●────────●────────●──
        \      /  \      /
         feat/...   fix/...
```

---

## 2. 브랜치 이름

```
<type>/<scope>-<설명>
```

| 요소 | 규칙 |
|------|------|
| `type` | 아래 타입 표 중 하나 |
| `scope` | `api` / `web` / `infra`. 여러 모듈에 걸친 작업은 생략 |
| 설명 | 영어 소문자, 단어는 `-`로 연결, 3~5단어 |

### 타입

| 타입 | 용도 |
|------|------|
| `feat` | 기능 추가 |
| `fix` | 버그 수정 |
| `refactor` | 동작 변경 없는 구조 개선 |
| `test` | 테스트 추가·수정 |
| `docs` | 문서 |
| `chore` | 빌드, 의존성, 설정, 인프라 |

### 표기 원칙
- Dependabot PR은 Dependabot 규칙(`dependabot/...`)을 그대로 사용 (예외)
- `feature`가 아닌 `feat` 사용. Conventional Commits 커밋 타입과 단어 통일, 다른 타입과 길이 균형
- 한글, 대문자, 공백, `_` 사용 금지. 셸, CI, Docker 태그에서 문제 소지
- 이슈 사용 시 번호 포함: `feat/api-12-schedule-polling`

### 예시

| 작업 | 브랜치 |
|------|--------|
| 요청 실행기 구현 | `feat/api-request-executor` |
| 스케줄 폴링 실행 | `feat/api-schedule-polling` |
| Assertion 판정 | `feat/api-assertion-evaluator` |
| 실패 알림 | `feat/api-failure-notification` |
| 케이스 등록 화면 | `feat/web-case-editor` |
| 실행 이력 화면 | `feat/web-run-history` |
| 크론 시간대 오류 수정 | `fix/api-schedule-timezone` |
| docker-compose 추가 | `chore/infra-docker-compose` |
| README 스택 버전 갱신 | `docs/readme-stack-versions` |

---

## 3. 커밋 메시지

브랜치와 동일한 타입·스코프 사용 ([Conventional Commits](https://www.conventionalcommits.org/ko/v1.0.0/))

```
<type>(<scope>): <요약>
```

```
feat(api): add schedule polling
fix(web): fix run history pagination
chore(infra): add docker-compose
docs: add branch convention
```

- 영어로 작성
- 요약은 명령형 현재 시제 (`add`, `fix`, `remove`), 소문자 시작, 마침표 없음
- 요약은 50자 이내. 상세 설명이 필요하면 한 줄 비우고 본문 작성
- 호환성을 깨는 변경은 타입 뒤 `!` 표기: `feat(api)!: change run result response format`

### PR

- 제목은 영어. 커밋 메시지와 같은 형식 (`<type>(<scope>): <요약>`)
- 제목 예시: `feat(web): add catalog editor pages`, `refactor(api): make request DTOs package-private`
- 본문은 한글. `## 요약`(변경 내용), `## 테스트`(확인 항목 체크리스트) 구성
- 본문에 도구 자동 생성 문구(예: `Generated with ...`) 미포함

---

## 4. 운영 원칙

- 기능 1개당 브랜치 1개. 수명은 짧게 유지
- 머지는 Merge commit (GitHub **Create a merge commit**). Squash merge·Rebase merge 미사용
  - 작업 브랜치의 개별 커밋 이력 보존. 변경 경위 추적 및 `git bisect` 활용 가능
  - 머지 커밋에 PR 번호가 남아 기능 단위 묶음 식별 가능
  - 기능 단위 이력 조회는 `git log --first-parent main`. 변경 내역 정리 시 활용
  - 개별 커밋이 그대로 `main`에 남으므로 작업 브랜치 커밋도 메시지 규칙 준수
- 머지 후 작업 브랜치 삭제
- `main` 직접 커밋은 초기 세팅(첫 커밋)에 한정

---

## 5. 머지 기준

판단 기준은 머지 후에도 `main`이 실행 가능한 상태인지 여부

### 머지 조건
아래 항목 모두 충족 시 즉시 머지. 1인 개발이므로 별도 승인 대기 없음

| 항목 | 확인 방법 |
|------|-----------|
| 작업 단위 완결 | 브랜치 이름에 명시한 작업의 동작 확인 |
| CI 통과 | PR의 **CI** 체크 통과 (`.github/workflows/ci.yml`: web lint·서식·테스트·빌드, api 테스트, E2E, 배포 스크립트) |
| 취약점 점검 통과 | 의존성을 바꾼 PR은 **Security** 체크 통과 (`.github/workflows/security.yml`). 실패하면 표의 패키지를 수정 버전으로 올림. 모든 PR은 **dependency review**(새로 들어온 npm·Actions 의존성) 통과 (`.github/workflows/dependencies.yml`) |
| 코드 분석 확인 | **CodeQL** 체크(`.github/workflows/codeql.yml`)가 새 경고를 남기면 PR의 경고를 확인하고 수정하거나, 오탐이면 Security → Code scanning에서 사유와 함께 닫음 |
| 빌드·테스트 통과 (로컬) | api: `./gradlew build` / web: `npm run lint`, `npm run format:check`, `npm test`, `npm run build` |
| 기동 확인 | 로컬 실행 후 변경 부분 동작 확인 |
| 셀프 리뷰 | PR **Files changed** 탭에서 diff 확인 (디버그 코드, 시크릿, 불필요한 파일) |
| 문서 반영 | 환경변수·실행 방법 변경 시 README 동시 수정 |

문서만 변경한 브랜치는 셀프 리뷰만으로 머지

### 머지 주기
- 브랜치 생성 후 1~3일 이내 머지 목표. 수명이 길수록 `main`과의 차이 누적
- 큰 기능은 `main`을 깨지 않는 조각으로 분할 머지
- 미완성이더라도 비활성 상태로 기존 동작에 영향이 없으면 머지 가능

분할 예시 (스케줄 기능)

| 순서 | 브랜치 | 내용 |
|------|--------|------|
| 1 | `feat/api-test-schedule-entity` | 엔티티·테이블 추가 |
| 2 | `feat/api-schedule-polling` | 폴링 로직 추가 (`enabled=false`) |
| 3 | `feat/api-schedule-execution` | 실제 실행 연결 |

### 머지 금지
- 빌드 또는 테스트 실패
- 기존 기능 동작 불량
- "일단 머지 후 수정" 전제의 미완성 상태. 브랜치에서 작업 지속

### 머지 후
1. 원격 작업 브랜치 삭제 (PR 화면 **Delete branch**)
2. 로컬 정리

   ```bash
   git switch main && git pull && git branch -d <브랜치명>
   ```

   Merge commit 방식이므로 `git pull` 후 `-d`로 삭제 가능. 거부 시 PR 머지 여부 재확인

3. api 변경 시 노트북 상시 실행 컨테이너 새 버전으로 교체

---

## 6. 릴리스

운영 서버는 main이 아니라 릴리스 태그를 받아 배포 (`docs/deploy.md` "업데이트"). main에 머지된 변경은 다음 릴리스 전까지 운영에 가지 않음

### 버전 규칙
`MAJOR.MINOR.PATCH` (Semantic Versioning). 첫 릴리스 1.0.0 (2026-10-17)

| 올리는 자리 | 변경 |
|------|------|
| PATCH | 버그·보안 수정, 의존성 업데이트 |
| MINOR | 기능 추가, 하위 호환인 설정·API 추가 |
| MAJOR | 호환이 깨지는 변경: 설정 이름 변경·삭제, API 응답 변경(CI 연동 스크립트 영향), 이전 버전으로 되돌릴 수 없는 DB 마이그레이션(열 삭제·이름 변경) |

- 버전은 파일에 직접: `api/build.gradle`의 `version`과 `web/package.json`의 `version` (항상 같은 값). 화면 "시스템 상태"와 요청의 `User-Agent`(`Sentinel/1.0.0`)에 표시
- 두 파일이 다르면 CI의 scripts 작업이 실패 (`scripts/release-version.sh --check`)
- 다음 릴리스 전까지 main의 빌드도 지난 버전 번호로 표시 (`-SNAPSHOT` 미사용). 운영 서버의 버전은 태그로 받은 것이므로 정확함

### 절차
1. 릴리스 PR: `chore/infra-release-1.0.1` 브랜치에서 두 파일의 버전 변경

   ```bash
   sed -i '' "s/^version = '.*'$/version = '1.0.1'/" api/build.gradle   # 리눅스는 sed -i
   (cd web && npm version 1.0.1 --no-git-tag-version)
   scripts/release-version.sh --check
   ```

   README 스택 버전 표도 실제 버전으로 (`web/package-lock.json`, Gradle 해석 결과, Gradle Wrapper 기준. Java 25 필요)

   ```bash
   scripts/stack-versions.sh         # 다른 버전 목록, 모두 같으면 종료 코드 0
   scripts/stack-versions.sh --fix   # 다른 버전을 README에 반영
   ```

   - 확인할 행은 `scripts/stack-versions.list`. README 표에 행을 더하거나 이름을 바꾸면 목록도 함께 (CI의 scripts 작업이 없는 행을 찾음)
   - PR마다 확인하지 않는 이유: 의존성 업데이트 PR(Dependabot)은 README를 고치지 않으므로 매번 실패함

   커밋 `chore: release 1.0.1`. PR 본문에 지난 태그 이후 변경 요약 (`git log --first-parent --oneline v1.0.0..main`)
2. 머지 후 그 머지 커밋에 태그

   ```bash
   git switch main && git pull --ff-only
   git tag -a v1.0.1 -m "Sentinel 1.0.1"
   git push origin v1.0.1
   ```

3. **Release** 워크플로(`.github/workflows/release.yml`)가 태그와 파일의 버전이 같은지 확인. 다르면 실패로 표시. 서버가 받기 전이면 태그를 지우고(`git tag -d v1.0.1 && git push origin :refs/tags/v1.0.1`) 1부터 다시
4. 서버 업데이트는 그 태그로 (`docs/deploy.md` "업데이트")

- 서버가 받은 태그는 고치지 않음. 잘못 만든 릴리스는 새 PATCH 버전으로 바로잡음
