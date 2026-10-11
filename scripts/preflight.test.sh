#!/usr/bin/env bash
# scripts/preflight.sh 테스트. 임시 폴더에 설정 파일과 인증서를 만들어 점검 결과 확인
#
#   bash scripts/preflight.test.sh
#
# OpenSSL(리눅스·CI)과 LibreSSL(macOS 기본) 모두에서 동작
#
# check의 조건은 결과가 나온 뒤 평가하도록 작은따옴표로 전달 (eval)
# shellcheck disable=SC2016
set -uo pipefail

SCRIPT="$(cd "$(dirname "$0")" && pwd)/preflight.sh"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

passed=0
failed=0

# 출력에 나오면 안 되는 값 (비밀번호·Webhook 주소·생존 신호 주소·암호화 키)
DB_PASSWORD_VALUE="db-password-must-not-appear"
WEBHOOK_VALUE="https://hooks.example.com/services/must-not-appear"
HEARTBEAT_VALUE="https://hc-ping.example.com/heartbeat-must-not-appear"
SECRET_KEY_VALUE=$(printf '0123456789abcdef0123456789abcdef' | openssl base64 -A)
PREVIOUS_KEY_VALUE=$(printf 'fedcba9876543210fedcba9876543210' | openssl base64 -A)

# ---------- 인증서 ----------

# 인증 기관 (중간 인증서 역할)
openssl req -x509 -newkey rsa:2048 -nodes -days 30 -subj "/CN=Test Company CA" \
  -keyout "$WORK/ca.key" -out "$WORK/ca.crt" >/dev/null 2>&1

# 인증 기관이 서명한 서버 인증서. $1 이름, $2 SAN, $3 유효 일수
issue() {
  openssl req -newkey rsa:2048 -nodes -subj "/CN=$1" -keyout "$WORK/$1.key" -out "$WORK/$1.csr" >/dev/null 2>&1
  printf 'subjectAltName=%s\n' "$2" >"$WORK/$1.ext"
  openssl x509 -req -in "$WORK/$1.csr" -CA "$WORK/ca.crt" -CAkey "$WORK/ca.key" -CAcreateserial \
    -days "$3" -extfile "$WORK/$1.ext" -out "$WORK/$1.crt" >/dev/null 2>&1
}

issue server "DNS:sentinel.team.internal,IP:10.0.0.5" 400
issue wildcard "DNS:*.team.internal" 400
issue soon "DNS:sentinel.team.internal" 10
issue other "DNS:sentinel.team.internal" 400

# 이미 만료된 인증서. req·x509는 과거 날짜를 지정할 수 없어 ca 명령 사용
mkdir -p "$WORK/ca-db"
: >"$WORK/ca-db/index.txt"
echo 01 >"$WORK/ca-db/serial"
cat >"$WORK/ca.cnf" <<EOF
[ca]
default_ca = test
[test]
dir = $WORK/ca-db
database = \$dir/index.txt
serial = \$dir/serial
new_certs_dir = \$dir
certificate = $WORK/ca.crt
private_key = $WORK/ca.key
default_md = sha256
policy = anything
copy_extensions = copy
[anything]
commonName = supplied
[ext]
subjectAltName = DNS:sentinel.team.internal
EOF
openssl req -newkey rsa:2048 -nodes -subj "/CN=expired" -keyout "$WORK/expired.key" -out "$WORK/expired.csr" >/dev/null 2>&1
openssl ca -batch -config "$WORK/ca.cnf" -extensions ext -in "$WORK/expired.csr" -out "$WORK/expired.crt" \
  -startdate 20240101000000Z -enddate 20240201000000Z >/dev/null 2>&1

# ---------- 설정 ----------

# 문제없는 설정 한 벌. $1 폴더, $2 인증서 이름(서버 인증서 + 중간 인증서)
valid_setup() {
  local dir=$1 name=${2:-server}
  rm -rf "$dir"
  mkdir -p "$dir/proxy/certs"
  cat "$WORK/$name.crt" "$WORK/ca.crt" >"$dir/proxy/certs/cert.pem"
  cp "$WORK/$name.key" "$dir/proxy/certs/key.pem"
  cat >"$dir/.env" <<EOF
# 주석
SENTINEL_SITE_ADDRESS=sentinel.team.internal
SENTINEL_TLS=/certs/cert.pem /certs/key.pem
SENTINEL_HSTS_MAX_AGE=31536000
EOF
  cat >"$dir/sentinel.env" <<EOF
DB_URL=jdbc:postgresql://dev.abc123.ap-northeast-2.rds.amazonaws.com:5432/app?currentSchema=sentinel&sslmode=require
DB_USERNAME=sentinel
DB_PASSWORD=$DB_PASSWORD_VALUE
SENTINEL_WEBHOOK_URL=$WEBHOOK_VALUE
SENTINEL_HEARTBEAT_URL=$HEARTBEAT_VALUE
SENTINEL_WEB_URL=https://sentinel.team.internal
SENTINEL_ADMIN_EMAIL=admin@example.com
SENTINEL_SECRET_KEY=$SECRET_KEY_VALUE
EOF
}

# 키 값 바꾸기 (없으면 추가). $1 파일, $2 키, $3 값
set_value() {
  grep -v "^$2=" "$1" >"$1.tmp"
  printf '%s=%s\n' "$2" "$3" >>"$1.tmp"
  mv "$1.tmp" "$1"
}

remove_value() {
  grep -v "^$2=" "$1" >"$1.tmp"
  mv "$1.tmp" "$1"
}

# ---------- 확인 ----------

run() {
  output=$(SENTINEL_ROOT="$1" bash "$SCRIPT" 2>&1)
  status=$?
}

check() {
  local description=$1 condition=$2
  if eval "$condition"; then
    passed=$((passed + 1))
  else
    failed=$((failed + 1))
    printf 'FAIL: %s\n  조건: %s\n  종료 코드: %s\n%s\n\n' "$description" "$condition" "$status" "$output"
  fi
}

contains() { printf '%s' "$output" | grep -qF -- "$1"; }

D="$WORK/site"

valid_setup "$D"
run "$D"
check "문제없는 설정은 통과" '[ "$status" -eq 0 ] && contains "실패 0 · 경고 0"'
check "주소를 인증서가 포함" 'contains "✔ 인증서가 sentinel.team.internal 포함"'
check "중간 인증서 포함 확인" 'contains "✔ cert.pem에 중간 인증서 포함 (2개)"'
check "비밀번호는 출력하지 않음" '! contains "$DB_PASSWORD_VALUE"'
check "Webhook 주소는 출력하지 않음" '! contains "$WEBHOOK_VALUE" && ! contains "must-not-appear"'
check "생존 신호 주소는 출력하지 않음" '! contains "$HEARTBEAT_VALUE" && ! contains "heartbeat-must-not-appear"'
check "암호화 키는 출력하지 않음" '! contains "$SECRET_KEY_VALUE"'

valid_setup "$D"
set_value "$D/.env" SENTINEL_SITE_ADDRESS "sentinel.team.internal, 10.0.0.5"
run "$D"
check "IP 주소도 SAN으로 확인" '[ "$status" -eq 0 ] && contains "✔ 인증서가 10.0.0.5 포함"'

valid_setup "$D"
set_value "$D/.env" SENTINEL_SITE_ADDRESS "https://monitor.team.internal:443"
run "$D"
check "인증서에 없는 주소는 실패" '[ "$status" -eq 1 ] && contains "✘ 인증서가 monitor.team.internal 을(를) 포함하지 않음"'

valid_setup "$D" wildcard
set_value "$D/.env" SENTINEL_SITE_ADDRESS "monitor.team.internal, a.b.team.internal"
run "$D"
check "와일드카드는 한 단계 하위 이름 포함" 'contains "✔ 인증서가 monitor.team.internal 포함"'
check "와일드카드는 두 단계 하위 이름 미포함" 'contains "✘ 인증서가 a.b.team.internal 을(를) 포함하지 않음"'

valid_setup "$D"
cp "$WORK/other.key" "$D/proxy/certs/key.pem"
run "$D"
check "짝이 맞지 않는 키는 실패" '[ "$status" -eq 1 ] && contains "✘ 개인 키가 인증서와 짝이 맞지 않음"'

valid_setup "$D"
rm "$D/proxy/certs/key.pem"
run "$D"
check "인증서 파일이 없으면 실패" '[ "$status" -eq 1 ] && contains "✘ 인증서 파일 없음"'

valid_setup "$D" expired
run "$D"
check "만료된 인증서는 실패" '[ "$status" -eq 1 ] && contains "✘ 인증서 만료됨"'

valid_setup "$D" soon
run "$D"
check "곧 만료되는 인증서는 경고" '[ "$status" -eq 0 ] && contains "! 인증서가 30일 안에 만료"'

valid_setup "$D"
cp "$WORK/server.crt" "$D/proxy/certs/cert.pem"
run "$D"
check "중간 인증서가 없으면 경고" '[ "$status" -eq 0 ] && contains "! cert.pem에 인증서가 1개"'

valid_setup "$D"
set_value "$D/.env" SENTINEL_SITE_ADDRESS "localhost, sentinel.team.internal"
run "$D"
check "보유 인증서에 localhost를 쓰면 경고" 'contains "! localhost는 보유 인증서에 없어"'

valid_setup "$D"
set_value "$D/.env" SENTINEL_TLS internal
set_value "$D/.env" SENTINEL_SITE_ADDRESS localhost
run "$D"
check "내부 인증서에 HSTS를 쓰면 경고" 'contains "! 내부 인증서에 HSTS 사용"'
check "localhost뿐이면 경고" 'contains "! 접속 주소가 localhost뿐"'

valid_setup "$D"
set_value "$D/.env" SENTINEL_HSTS_MAX_AGE "1y"
run "$D"
check "HSTS 값은 숫자" '[ "$status" -eq 1 ] && contains "✘ SENTINEL_HSTS_MAX_AGE는 초 단위 숫자"'

valid_setup "$D"
set_value "$D/.env" SENTINEL_TLS "/etc/cert.pem /etc/key.pem"
run "$D"
check "인증서 경로 형식 오류는 실패" '[ "$status" -eq 1 ] && contains "✘ SENTINEL_TLS 형식 오류"'

valid_setup "$D"
rm "$D/.env"
run "$D"
check ".env가 없으면 실패" '[ "$status" -eq 1 ] && contains "✘ .env 없음"'

valid_setup "$D"
remove_value "$D/sentinel.env" DB_PASSWORD
run "$D"
check "DB 비밀번호가 없으면 실패" '[ "$status" -eq 1 ] && contains "✘ DB_PASSWORD 비어 있음"'

valid_setup "$D"
set_value "$D/sentinel.env" DB_PASSWORD "\"$DB_PASSWORD_VALUE\""
run "$D"
check "따옴표 값은 키 이름만 알리고 실패" '[ "$status" -eq 1 ] && contains "✘ sentinel.env의 DB_PASSWORD 값에 따옴표 사용"'
check "따옴표 오류에도 값은 출력하지 않음" '! contains "$DB_PASSWORD_VALUE"'

valid_setup "$D"
set_value "$D/sentinel.env" DB_URL "jdbc:postgresql://localhost:5432/sentinel"
run "$D"
check "컨테이너 안의 localhost DB는 실패" '[ "$status" -eq 1 ] && contains "✘ DB_URL이 localhost"'

valid_setup "$D"
set_value "$D/sentinel.env" DB_URL "jdbc:postgresql://dev.rds.amazonaws.com:5432/app?currentSchema=sentinel"
run "$D"
check "RDS 주소에 sslmode가 없으면 경고" '[ "$status" -eq 0 ] && contains "! DB_URL에 sslmode 없음"'
check "DB 주소는 호스트만 표시" 'contains "✔ DB: dev.rds.amazonaws.com"'

valid_setup "$D"
set_value "$D/sentinel.env" SENTINEL_SECRET_KEY "$(printf 'short' | openssl base64 -A)"
run "$D"
check "암호화 키 길이 오류는 실패" '[ "$status" -eq 1 ] && contains "✘ SENTINEL_SECRET_KEY는 Base64로 16·24·32바이트"'

valid_setup "$D"
set_value "$D/sentinel.env" SENTINEL_SECRET_KEY_PREVIOUS "$PREVIOUS_KEY_VALUE"
run "$D"
check "키 교체 중이면 이전 키 제거 안내" '[ "$status" -eq 0 ] && contains "! SENTINEL_SECRET_KEY_PREVIOUS 설정됨 (키 교체 중)"'
check "이전 키는 출력하지 않음" '! contains "$PREVIOUS_KEY_VALUE"'

valid_setup "$D"
set_value "$D/sentinel.env" SENTINEL_SECRET_KEY_PREVIOUS "$SECRET_KEY_VALUE"
run "$D"
check "이전 키가 현재 키와 같으면 경고" 'contains "! SENTINEL_SECRET_KEY_PREVIOUS가 SENTINEL_SECRET_KEY와 같음"'

valid_setup "$D"
set_value "$D/sentinel.env" SENTINEL_SECRET_KEY_PREVIOUS "$(printf 'short' | openssl base64 -A)"
run "$D"
check "이전 키 길이 오류는 실패" '[ "$status" -eq 1 ] && contains "✘ SENTINEL_SECRET_KEY_PREVIOUS는 Base64로 16·24·32바이트"'

valid_setup "$D"
set_value "$D/sentinel.env" SENTINEL_SECRET_KEY ""
set_value "$D/sentinel.env" SENTINEL_SECRET_KEY_PREVIOUS "$PREVIOUS_KEY_VALUE"
run "$D"
check "새 키 없이 이전 키만 있으면 실패" '[ "$status" -eq 1 ] && contains "✘ SENTINEL_SECRET_KEY_PREVIOUS만 있음"'

valid_setup "$D"
set_value "$D/sentinel.env" SENTINEL_ADMIN_PASSWORD "initial-admin-password"
run "$D"
check "최초 관리자 비밀번호가 남아 있으면 경고" 'contains "! SENTINEL_ADMIN_PASSWORD 설정됨" && ! contains "initial-admin-password"'

valid_setup "$D"
set_value "$D/sentinel.env" SENTINEL_WEB_URL "https://localhost"
run "$D"
check "알림 링크 주소가 접속 주소와 다르면 경고" 'contains "! SENTINEL_WEB_URL(localhost)이 접속 주소에 없음"'

valid_setup "$D"
set_value "$D/sentinel.env" SENTINEL_HEARTBEAT_URL ""
run "$D"
check "생존 신호 주소가 없으면 경고" '[ "$status" -eq 0 ] && contains "! SENTINEL_HEARTBEAT_URL 없음"'

printf '%d 통과, %d 실패\n' "$passed" "$failed"
[ "$failed" -eq 0 ]
