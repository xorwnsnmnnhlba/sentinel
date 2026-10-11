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
| 취약점 점검 통과 | 의존성을 바꾼 PR은 **Security** 체크 통과 (`.github/workflows/security.yml`). 실패하면 표의 패키지를 수정 버전으로 올림 |
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
