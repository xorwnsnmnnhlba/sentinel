# 배포·운영

사용자가 내부망에서 쓰도록 서버에 띄우고 유지하는 절차. 컨테이너 구성과 설계 배경은 [design.md](design.md) "6. 실행 환경"

최종 점검: 2026-10-17 (새 서버 리허설: 이 문서 그대로 설치·`$`·` #`가 든 DB 비밀번호·재부팅·업데이트·키 교체·교체 전 백업 복원 확인. 2026-10-11에는 인증서 교체까지 확인)

## 결정 사항 (2026-09-26)

| 항목 | 결정 | 비고 |
|------|------|------|
| 서버 | 미정 | Docker Compose가 동작하는 상시 켜진 서버 (리눅스 권장). 아래 절차는 서버 종류와 무관 |
| DB | AWS RDS의 `sentinel` 스키마 | `sslmode=require`, 백업은 RDS 자동 백업·스냅샷 |
| 접속 주소·인증서 | 내부 DNS 이름 + 보유 인증서(기관 발급) | 사용자 PC가 이미 신뢰하므로 루트 인증서 배포 불필요 |
| HSTS | 사용 (보유 인증서 전제) | 처음에는 짧게(300초) 확인 후 1년으로 늘림 |
| 업데이트 | 서버에서 릴리스 태그(`v1.0.0` 등)를 받아 빌드 (2026-10-17) | 이미지 레지스트리 미사용. main에 머지된 변경은 다음 릴리스 태그 전까지 운영에 가지 않음 (`docs/branch-convention.md` "6. 릴리스") |

## 서버 준비

- Docker Engine과 Compose v2, git, openssl
- 재부팅 후 자동 복구: Docker 서비스 자동 시작(`systemctl enable docker`). 컨테이너는 `restart: unless-stopped`로 함께 시작
- 내부 DNS: 접속 이름(예: `sentinel.<내부 도메인>`)을 서버 IP로 등록
- 방화벽: 443 허용 (HTTP로 들어와도 HTTPS로 이동시키려면 80도). web 3000·api 8080은 `127.0.0.1`에만 열려 있어 추가 조치 불필요
- RDS 보안 그룹: 서버 IP에서 5432 인바운드 허용
- 시계 동기화(NTP): 인증서 유효기간 판정·스케줄 시각의 기준
- 디스크: 이미지 빌드 여유 공간. 컨테이너 로그는 서비스당 최대 50MB(10MB × 5)로 순환

## 최초 배포

```bash
git clone https://github.com/xorwnsnmnnhlba/sentinel.git && cd sentinel
git tag --sort=-v:refname | head -n 3   # 최신 릴리스 확인
git checkout v1.1.0                      # 최신 릴리스 태그로
```

- 서버는 브랜치가 아니라 태그를 받으므로 git이 "detached HEAD"라고 안내하는 것이 정상

1. 보유 인증서 배치 (`proxy/certs/`, git 제외). 새로 받은 저장소에는 폴더가 없으므로 `mkdir -p proxy/certs`
   - `cert.pem`: 서버 인증서 뒤에 중간 인증서를 이어 붙인 파일 (서버 → 중간 순). 중간 인증서가 빠지면 일부 PC에서 신뢰 오류
   - `key.pem`: 암호가 없는 개인 키. `chmod 600 proxy/certs/key.pem`
2. `.env` 작성 (`cp .env.example .env`)
   - `SENTINEL_SITE_ADDRESS=sentinel.<내부 도메인>`: 인증서에 있는 이름만. localhost는 보유 인증서에 없어 제외
   - `SENTINEL_TLS=/certs/cert.pem /certs/key.pem`
   - `SENTINEL_HSTS_MAX_AGE=300`: 처음에는 5분. 확인을 마치면 31536000(1년)으로 변경 후 `docker compose up -d sentinel-proxy`
3. `sentinel.env` 작성 (`cp sentinel.env.example sentinel.env`, `chmod 600 sentinel.env`)
   - 값에 `$`·`\`·` #`가 있으면 작은따옴표로 감쌈 (`DB_PASSWORD='pa$word'`). 따옴표가 없거나 큰따옴표면 docker compose가 `$이름`을 변수로 바꿔(`pa$word` → `pa`) DB 접속 실패. `.env`도 같은 규칙
   - `DB_URL`: `jdbc:postgresql://<RDS 엔드포인트>:5432/<DB>?currentSchema=sentinel&sslmode=require`
   - `DB_USERNAME`·`DB_PASSWORD`: DB 계정
   - `SENTINEL_SECRET_KEY`: `openssl rand -base64 32`로 생성. 환경 시크릿(토큰·API 키)과 프로젝트별 알림 Webhook 주소를 암호화. 없으면 둘 다 등록할 수 없음. 분실하면 저장된 값을 복호화할 수 없으므로 비밀번호 관리 도구에 따로 보관
   - `SENTINEL_ADMIN_EMAIL`·`SENTINEL_ADMIN_PASSWORD`: 사용자가 없을 때 1회 생성되는 최초 관리자
   - `SENTINEL_WEB_URL=https://sentinel.<내부 도메인>`: 실패 알림의 결과 링크
   - `SENTINEL_WEBHOOK_URL`: 기본 알림 채널. Slack·Teams 수신 Webhook (주소 자체가 자격증명). 프로젝트 Webhook이 없는 프로젝트의 실패·복구 알림, DB 연결 실패·복구, 모니터링 공백, 주간 요약(월 09:00)이 이 채널로. 없으면 로그에만 남음
   - `SENTINEL_HEARTBEAT_URL`: 외부 감시 서비스의 생존 신호 주소 (주소 자체가 자격증명). 서버가 멈추면 외부 서비스가 알림 (`api/README.md` "외부 생존 신호")
4. 점검: `scripts/preflight.sh`의 마지막 줄 `Failures: 0` 확인. 값은 출력하지 않음 (출력은 영어)
5. 기동: `docker compose up -d --build --wait`
6. 확인 (다른 PC 브라우저)
   - `https://<접속 이름>` 접속 시 인증서 경고 없음, 로그인 유지
   - `http://<접속 이름>`은 `https://`로 이동
   - 사이드바 "시스템 상태"(관리자): 암호화 키 "사용 중", 알림·감시 설정이 모두 "설정됨"(생존 신호는 설정한 경우), 보관 기간 (없는 항목은 `sentinel.env` 확인)
   - 사이드바 "알림 이력"의 "기본 채널 테스트 알림"(관리자)으로 기본 채널에 메시지가 오는지 확인
   - 생존 신호를 설정했다면 외부 감시 서비스에 첫 신호가 도착 (스케줄 폴링 주기 60초)
7. 최초 관리자로 로그인 → 비밀번호 변경 → `sentinel.env`에서 `SENTINEL_ADMIN_PASSWORD` 줄 삭제 후 `docker compose up -d sentinel-api` → 사용자 화면에서 사용자 초대
   - 줄만 지우면 실행 중인 컨테이너의 환경변수(`docker inspect`에 표시)에는 남아 있으므로 컨테이너를 다시 만들어 제거
8. HSTS를 1년(31536000)으로 변경

`scripts/preflight.sh` 확인 항목

| 구분 | 확인 |
|------|------|
| `.env` | 값 작성 형식(아래 `sentinel.env`와 같음), 접속 주소, 인증서 방식 형식, HSTS 값 (내부 인증서와 함께 쓰면 경고) |
| 보유 인증서 | 만료(30일 전 경고), 개인 키 짝, 개인 키 권한(다른 계정이 읽을 수 있으면 경고), 접속 주소마다 인증서 포함 여부(와일드카드는 한 단계), 중간 인증서 포함 |
| `sentinel.env` | 파일 권한(다른 계정이 읽을 수 있으면 경고), 값 작성 형식(docker compose가 바꾸는 `$`·` #`·큰따옴표 안의 `\`, 닫히지 않은 작은따옴표), DB 필수 값, DB 주소(컨테이너 안 localhost 금지, RDS의 sslmode: 없거나 `prefer` 등 평문 가능 값이면 경고, 모르는 값이면 실패), 암호화 키 길이, 이전 암호화 키(길이·새 키 유무·잔존), 최초 관리자 비밀번호 잔존, 알림 링크 주소, 기본 알림 채널·생존 신호 주소 유무 (값은 출력하지 않음) |

## 운영 시작 설정

배포 후 관리자가 화면에서 1회 설정

1. 사용자 초대: 사이드바 "사용자" → "사용자 초대"로 발급한 링크 전달 (메일 발송 없음)
2. 프로젝트별 알림 채널: 프로젝트 화면 "알림 채널"에서 Webhook 지정 → "테스트 알림 보내기"로 채널에 메시지가 오는지 확인. 지정하지 않은 프로젝트는 기본 채널
3. 휴일: 사이드바 "휴일" → "여러 건 붙여넣기"로 올해 남은 공휴일과 내년 공휴일, 자체 휴일 등록 (한 줄에 "날짜 이름"). 대체·임시공휴일은 발표 때마다 추가
4. 휴일 건너뛰기: 사이드바 "스케줄"에서 평일 업무 시간 스케줄을 골라 "휴일 건너뛰기 켜기" (24시간 감시 스케줄은 그대로)
5. CI 연동이 필요하면 사용자마다 "API 토큰" 발급 (`api/README.md` "API 토큰")

알림 확인

- 보낸 알림과 결과는 사이드바 "알림 이력". "알림이 안 왔다"는 먼저 여기서 확인 (보냄·HTTP 상태·응답 없는 사유와 확인할 점)
- 채널이 고장 나면(마지막 발송이 실패) 대시보드 맨 위에 "알림을 보내지 못하는 채널"로 표시. Webhook을 고친 뒤 테스트 알림이 성공하면 사라짐
- 기본 채널은 "알림 이력"의 "기본 채널 테스트 알림"(관리자)으로 확인. 실제 알림과 같은 경로로 보내고 이력에 남음

## 업데이트

보안 업데이트도 같은 절차. 매주 월요일 GitHub **Security** 점검(`.github/workflows/security.yml`)이 실패하면 새로 공개된 취약점이므로 의존성 업데이트 PR(Dependabot 또는 직접)을 머지한 뒤 반영

- 정기 점검이 실패하면 저장소에 "Weekly security check failed" 이슈가 열림 (저장소를 지켜보는(Watch) 사람에게 알림). 다시 통과하면 자동으로 닫힘
- 매주 CodeQL 정기 분석에서 main에 열린 코드 경고가 있으면 "Code scanning needs attention" 이슈가 열림. 경고 내용은 **Security → Code scanning**에서 확인하고, 고치거나 오탐이면 사유를 남겨 닫음. 0건이 되면 이슈도 자동으로 닫힘
- 공개 저장소는 60일 동안 활동(커밋 등)이 없으면 GitHub이 정기 실행을 멈춤. **Actions → Security**에 중지 안내가 보이면 **Enable workflow**

```bash
git fetch --tags
git describe --tags                      # 지금 버전
git tag --sort=-v:refname | head -n 3   # 새 릴리스 확인
# 새 DB 마이그레이션이 있으면 먼저 RDS 스냅샷
git diff --stat HEAD v1.1.0 -- api/src/main/resources/db/migration
git checkout v1.1.0                      # 올릴 릴리스 태그
scripts/preflight.sh
docker compose pull --ignore-buildable
docker compose build --pull
docker compose up -d --wait
docker image prune -f
```

- 베이스 이미지(JDK·Node·Caddy)는 같은 태그(`25-jre`·`24-alpine` 등)로 보안 패치가 나오므로 매번 새로 받음. `--build`만 쓰면 서버에 있던 이미지를 그대로 사용
- 중단 시간: 컨테이너 교체 동안(수십 초) 접속 불가. 그 사이 놓친 스케줄 실행은 기동 후 1회만 실행
- 바뀐 코드가 없어도 api 컨테이너는 매번 다시 시작 (jar에 빌드 시각이 들어가 이미지가 새로 만들어짐). web·앞단은 바뀐 것이 없으면 그대로
- 마이그레이션은 api 기동 시 Flyway가 적용. 실패하면 api가 기동하지 않고 `--wait`가 오류로 종료
- 확인: `docker compose ps`(모두 healthy), 화면 "시스템 상태"의 버전이 올린 릴리스(예: 1.1.0)이고 빌드 시각·DB 마이그레이션이 새 값, `docker compose logs --since 10m sentinel-api`

## 되돌리기

```bash
git checkout v1.0.1                      # 이전 릴리스 태그
docker compose up -d --build --wait
```

- 새 마이그레이션이 없던 업데이트: 그대로 되돌림 가능
- 새 마이그레이션이 있던 업데이트
  - 컬럼·테이블 추가만 있으면 대부분 이전 버전으로 기동 가능 (Flyway가 모르는 이후 버전 이력은 무시, 엔티티 검증은 남는 컬럼 허용)
  - 컬럼 삭제·이름 변경이 있으면 이전 버전의 엔티티 검증(`ddl-auto: validate`) 실패로 기동 불가. 수정 버전을 새로 배포하거나 업데이트 전 스냅샷으로 복원
  - RDS 스냅샷 복원은 새 인스턴스 생성 방식. 다른 서비스와 함께 쓰는 RDS라면 그 RDS 관리자와 협의
- 되돌린 뒤에는 수정이 담긴 다음 릴리스 태그로 업데이트

## 인증서 교체

보유 인증서는 자동 갱신되지 않음. `scripts/preflight.sh`가 만료 30일 전부터 경고

```bash
openssl x509 -in proxy/certs/cert.pem -noout -enddate
```

1. 새 `cert.pem`(서버 + 중간)·`key.pem`을 `proxy/certs/`에 덮어쓰기
2. `scripts/preflight.sh`로 주소 포함·키 짝 확인
3. `docker compose restart sentinel-proxy` (앞단만 재시작, api·web 영향 없음)

## 암호화 키 교체

키 유출이 의심되거나 키를 아는 사람이 바뀌었을 때. 저장된 환경 시크릿과 프로젝트 Webhook 주소를 다시 입력하지 않고 새 키로 옮김

```bash
openssl rand -base64 32
```

1. 위 명령으로 새 키 생성 후 비밀번호 관리 도구에 보관 (이전 키는 아래 7까지 유지)
2. `sentinel.env` 수정
   - `SENTINEL_SECRET_KEY_PREVIOUS=<지금까지의 키>`
   - `SENTINEL_SECRET_KEY=<새 키>`
3. `scripts/preflight.sh`: "SENTINEL_SECRET_KEY_PREVIOUS is set (key rotation in progress)" 경고 외 `Failures: 0` 확인
4. `docker compose up -d sentinel-api` (환경변수를 다시 읽도록 컨테이너 재생성)
5. 확인: 화면 "시스템 상태"의 암호화 키가 "키 교체 중"이고 풀 수 없는 값 없음. 로그로도 확인 가능

   ```bash
   docker compose logs --since 10m sentinel-api | grep -E "Re-encrypted|cannot be decrypted"
   ```

   - `Re-encrypted N secret value(s)`: 다시 암호화한 수. 저장된 값이 없거나 이미 새 키면 이 줄 없음
   - `cannot be decrypted` 경고가 있으면 이전 키가 틀렸거나 그 전에 이미 읽을 수 없던 값. 값은 지우지 않으므로 올바른 이전 키로 2~4 반복. 위치(`environment 3 secret 'token'`)의 값은 화면에서 다시 입력해도 됨
   - 화면에서 시크릿을 쓰는 스위트 실행, 프로젝트 "테스트 알림 보내기"
6. `sentinel.env`에서 `SENTINEL_SECRET_KEY_PREVIOUS` 줄 삭제 후 `docker compose up -d sentinel-api`. "시스템 상태"의 암호화 키가 "사용 중"
7. 이전 키는 교체 전에 만든 DB 백업이 모두 사라질 때까지 "폐기 예정"으로 보관 (RDS 자동 백업 보존 기간이 지나고, 교체 전 수동 스냅샷을 지운 뒤 폐기)
   - 교체 전 백업의 시크릿·Webhook은 이전 키로 암호화되어 있음. 이전 키를 버리면 그 백업으로 복원했을 때 다시 입력해야 함
   - 유출 의심으로 교체했다면 이전 키로 풀 수 있는 실제 값(토큰·Webhook)도 발급처에서 바꿈. 키만 바꾸면 백업과 유출된 사본의 값은 그대로 유효

- 다시 암호화는 api 기동 시 요청을 받기 전에 끝남. 실패하면 기동하지 않고 `--wait`가 오류
- 이전 키 없이 키만 바꾸면 기존 값을 읽지 못함 (시크릿을 쓰는 실행 400, 프로젝트 알림 실패). 이때도 이전 키를 넣고 다시 기동하면 복구
- 교체 전 백업으로 복원한 경우: 현재 키는 `SENTINEL_SECRET_KEY`에 둔 채 이전 키를 `SENTINEL_SECRET_KEY_PREVIOUS`에 넣고 기동하면 복원된 값을 새 키로 다시 암호화 (3~6과 같음)

## 백업·보관

| 대상 | 방법 |
|------|------|
| DB (케이스·실행 이력·사용자) | RDS 자동 백업 보존 기간 확인, 마이그레이션이 있는 업데이트 전 수동 스냅샷 |
| `SENTINEL_SECRET_KEY` | 비밀번호 관리 도구에 보관. 분실 시 환경의 시크릿과 프로젝트 Webhook을 모두 다시 입력. 바꿀 때는 "암호화 키 교체". 교체 전 키는 그 전에 만든 DB 백업이 남아 있는 동안 함께 보관 |
| 알림 발송 이력 | DB에 90일 보관 후 자동 삭제 (`sentinel.retention.notification-retention`). 주소·본문은 기록하지 않음 |
| 로그인 세션·초대·재설정 링크 | 만료되면 매일 정리 배치에서 자동 삭제 (해시만 저장) |
| `sentinel.env`·`.env`·`proxy/certs/` | 서버에만 존재. 서버 교체에 대비해 값의 보관 위치 기록 (파일째 공유 금지) |
| `caddy_data` 볼륨 | 보유 인증서 방식에서는 보관 불필요 (내부 인증서 방식일 때만 인증 기관 키 보관) |

## 로그

```bash
docker compose logs -f sentinel-api
docker compose logs --since 1h sentinel-proxy
```

- 서비스당 10MB 파일 5개까지 보관 후 오래된 것부터 삭제 (`docker-compose.yml`의 `x-logging`)
- 실행 결과의 요청·응답 원문은 로그가 아니라 DB에 저장하며 보관기간(기본 30일) 후 삭제
- 알림 발송 결과는 화면 "알림 이력"(90일)에서 먼저 확인. 로그에는 발송 실패만 WARN 1줄 (주소 없음)

```bash
docker compose logs --since 1d sentinel-api | grep "Notification delivery"
```
