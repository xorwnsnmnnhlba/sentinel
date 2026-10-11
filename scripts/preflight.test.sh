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
  chmod 600 "$dir/proxy/certs/key.pem"
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
  chmod 600 "$dir/sentinel.env"
}

# 키 값 바꾸기 (없으면 추가). $1 파일, $2 키, $3 값
# 원래 파일에 덮어써 권한(chmod 600) 유지. mv로 바꾸면 새 파일 권한이 되어 권한 경고가 섞임
set_value() {
  grep -v "^$2=" "$1" >"$1.tmp"
  printf '%s=%s\n' "$2" "$3" >>"$1.tmp"
  cat "$1.tmp" >"$1" && rm "$1.tmp"
}

remove_value() {
  grep -v "^$2=" "$1" >"$1.tmp"
  cat "$1.tmp" >"$1" && rm "$1.tmp"
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
    printf 'FAIL: %s\n  condition: %s\n  exit code: %s\n%s\n\n' "$description" "$condition" "$status" "$output"
  fi
}

contains() { printf '%s' "$output" | grep -qF -- "$1"; }

D="$WORK/site"

valid_setup "$D"
run "$D"
check "a valid setup passes" '[ "$status" -eq 0 ] && contains "Failures: 0, warnings: 0"'
check "the certificate covers the site address" 'contains "✔ Certificate covers sentinel.team.internal"'
check "detects the intermediate certificate" 'contains "✔ cert.pem includes the intermediate certificate (2 certificates)"'
check "does not print the password" '! contains "$DB_PASSWORD_VALUE"'
check "does not print the webhook URL" '! contains "$WEBHOOK_VALUE" && ! contains "must-not-appear"'
check "does not print the heartbeat URL" '! contains "$HEARTBEAT_VALUE" && ! contains "heartbeat-must-not-appear"'
check "does not print the encryption key" '! contains "$SECRET_KEY_VALUE"'

valid_setup "$D"
set_value "$D/.env" SENTINEL_SITE_ADDRESS "sentinel.team.internal, 10.0.0.5"
run "$D"
check "matches IP addresses against the SAN" '[ "$status" -eq 0 ] && contains "✔ Certificate covers 10.0.0.5"'

valid_setup "$D"
set_value "$D/.env" SENTINEL_SITE_ADDRESS "https://monitor.team.internal:443"
run "$D"
check "fails for an address the certificate does not cover" '[ "$status" -eq 1 ] && contains "✘ Certificate does not cover monitor.team.internal"'

valid_setup "$D" wildcard
set_value "$D/.env" SENTINEL_SITE_ADDRESS "monitor.team.internal, a.b.team.internal"
run "$D"
check "a wildcard covers one level of subdomain" 'contains "✔ Certificate covers monitor.team.internal"'
check "a wildcard does not cover two levels" 'contains "✘ Certificate does not cover a.b.team.internal"'

valid_setup "$D"
cp "$WORK/other.key" "$D/proxy/certs/key.pem"
run "$D"
check "fails for a key that does not match" '[ "$status" -eq 1 ] && contains "✘ Private key does not match the certificate"'

valid_setup "$D"
rm "$D/proxy/certs/key.pem"
run "$D"
check "fails when a certificate file is missing" '[ "$status" -eq 1 ] && contains "✘ Certificate file missing"'

valid_setup "$D" expired
run "$D"
check "fails for an expired certificate" '[ "$status" -eq 1 ] && contains "✘ Certificate expired"'

valid_setup "$D" soon
run "$D"
check "warns when the certificate expires soon" '[ "$status" -eq 0 ] && contains "! Certificate expires within 30 days"'

valid_setup "$D"
cp "$WORK/server.crt" "$D/proxy/certs/cert.pem"
run "$D"
check "warns without an intermediate certificate" '[ "$status" -eq 0 ] && contains "! cert.pem holds 1 certificate"'

valid_setup "$D"
set_value "$D/.env" SENTINEL_SITE_ADDRESS "localhost, sentinel.team.internal"
run "$D"
check "warns about localhost with certificate files" 'contains "! localhost is not in the certificate"'

valid_setup "$D"
set_value "$D/.env" SENTINEL_TLS internal
set_value "$D/.env" SENTINEL_SITE_ADDRESS localhost
run "$D"
check "warns about HSTS with the internal CA" 'contains "! HSTS with the internal CA"'
check "warns when the site address is localhost only" 'contains "! Site address is localhost only"'

valid_setup "$D"
set_value "$D/.env" SENTINEL_HSTS_MAX_AGE "1y"
run "$D"
check "HSTS max age must be a number" '[ "$status" -eq 1 ] && contains "✘ SENTINEL_HSTS_MAX_AGE must be a number of seconds"'

valid_setup "$D"
set_value "$D/.env" SENTINEL_TLS "/etc/cert.pem /etc/key.pem"
run "$D"
check "fails for an invalid certificate path format" '[ "$status" -eq 1 ] && contains "✘ SENTINEL_TLS has an invalid format"'

valid_setup "$D"
rm "$D/.env"
run "$D"
check "fails without .env" '[ "$status" -eq 1 ] && contains "✘ .env missing"'

valid_setup "$D"
remove_value "$D/sentinel.env" DB_PASSWORD
run "$D"
check "fails without a DB password" '[ "$status" -eq 1 ] && contains "✘ DB_PASSWORD is empty"'

valid_setup "$D"
set_value "$D/sentinel.env" DB_PASSWORD "\"$DB_PASSWORD_VALUE\""
run "$D"
check "fails for quoted values, naming only the key" '[ "$status" -eq 1 ] && contains "✘ sentinel.env: DB_PASSWORD value is quoted"'
check "does not print the quoted value" '! contains "$DB_PASSWORD_VALUE"'

valid_setup "$D"
set_value "$D/sentinel.env" DB_URL "jdbc:postgresql://localhost:5432/sentinel"
run "$D"
check "fails for a localhost DB inside the container" '[ "$status" -eq 1 ] && contains "✘ DB_URL points to localhost"'

valid_setup "$D"
set_value "$D/sentinel.env" DB_URL "jdbc:postgresql://dev.rds.amazonaws.com:5432/app?currentSchema=sentinel"
run "$D"
check "warns when an RDS address has no sslmode" '[ "$status" -eq 0 ] && contains "! DB_URL has no sslmode"'
check "shows only the DB host" 'contains "✔ DB: dev.rds.amazonaws.com"'

valid_setup "$D"
set_value "$D/sentinel.env" SENTINEL_SECRET_KEY "$(printf 'short' | openssl base64 -A)"
run "$D"
check "fails for an encryption key of the wrong length" '[ "$status" -eq 1 ] && contains "✘ SENTINEL_SECRET_KEY must be Base64 for 16, 24 or 32 bytes"'

valid_setup "$D"
set_value "$D/sentinel.env" SENTINEL_SECRET_KEY_PREVIOUS "$PREVIOUS_KEY_VALUE"
run "$D"
check "reminds to remove the previous key during rotation" '[ "$status" -eq 0 ] && contains "! SENTINEL_SECRET_KEY_PREVIOUS is set (key rotation in progress)"'
check "does not print the previous key" '! contains "$PREVIOUS_KEY_VALUE"'

valid_setup "$D"
set_value "$D/sentinel.env" SENTINEL_SECRET_KEY_PREVIOUS "$SECRET_KEY_VALUE"
run "$D"
check "warns when the previous key equals the current key" 'contains "! SENTINEL_SECRET_KEY_PREVIOUS equals SENTINEL_SECRET_KEY"'

valid_setup "$D"
set_value "$D/sentinel.env" SENTINEL_SECRET_KEY_PREVIOUS "$(printf 'short' | openssl base64 -A)"
run "$D"
check "fails for a previous key of the wrong length" '[ "$status" -eq 1 ] && contains "✘ SENTINEL_SECRET_KEY_PREVIOUS must be Base64 for 16, 24 or 32 bytes"'

valid_setup "$D"
set_value "$D/sentinel.env" SENTINEL_SECRET_KEY ""
set_value "$D/sentinel.env" SENTINEL_SECRET_KEY_PREVIOUS "$PREVIOUS_KEY_VALUE"
run "$D"
check "fails with only the previous key" '[ "$status" -eq 1 ] && contains "✘ Only SENTINEL_SECRET_KEY_PREVIOUS is set"'

valid_setup "$D"
chmod 644 "$D/sentinel.env"
run "$D"
check "warns when other accounts can read sentinel.env" '[ "$status" -eq 0 ] && contains "! sentinel.env permissions -rw-r--r--" && contains "chmod 600 sentinel.env"'

valid_setup "$D"
chmod 640 "$D/proxy/certs/key.pem"
run "$D"
check "warns when the group can read the private key" '[ "$status" -eq 0 ] && contains "! key.pem permissions -rw-r-----" && contains "chmod 600 proxy/certs/key.pem"'

valid_setup "$D"
set_value "$D/sentinel.env" SENTINEL_ADMIN_PASSWORD "initial-admin-password"
run "$D"
check "warns while the initial admin password remains" 'contains "! SENTINEL_ADMIN_PASSWORD is set" && ! contains "initial-admin-password"'

valid_setup "$D"
set_value "$D/sentinel.env" SENTINEL_WEB_URL "https://localhost"
run "$D"
check "warns when the alert link address is not a site address" 'contains "! SENTINEL_WEB_URL (localhost) is not a site address"'

valid_setup "$D"
set_value "$D/sentinel.env" SENTINEL_HEARTBEAT_URL ""
run "$D"
check "warns without a heartbeat URL" '[ "$status" -eq 0 ] && contains "! SENTINEL_HEARTBEAT_URL missing"'

printf '%d passed, %d failed\n' "$passed" "$failed"
[ "$failed" -eq 0 ]
