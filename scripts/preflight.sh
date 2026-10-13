#!/usr/bin/env bash
# 배포 전 점검. 서버의 저장소 루트에서 실행 (docs/deploy.md "최초 배포")
#
#   scripts/preflight.sh
#
# 확인 항목: .env(접속 주소·인증서 방식·HSTS), 보유 인증서(주소 포함·키 짝·만료·중간 인증서·개인 키 권한), sentinel.env(파일 권한·필수 값·형식)
# 값은 출력하지 않음. 비밀번호·키·Webhook 주소는 있는지와 형식만 확인
# 실패(✘)가 있으면 종료 코드 1. 경고(!)는 배포는 가능하나 확인 필요
#
# 필요: bash, openssl(OpenSSL 1.1.1 이상 또는 LibreSSL), sed, grep
set -uo pipefail

ROOT="${SENTINEL_ROOT:-$(cd "$(dirname "$0")/.." && pwd)}"
ENV_FILE="$ROOT/.env"
APP_ENV_FILE="$ROOT/sentinel.env"
EXPIRY_WARNING_DAYS=30

failures=0
warnings=0

ok() { printf '  ✔ %s\n' "$1"; }
warn() {
  printf '  ! %s\n' "$1"
  warnings=$((warnings + 1))
}
fail() {
  printf '  ✘ %s\n' "$1"
  failures=$((failures + 1))
}
section() { printf '\n[%s]\n' "$1"; }

# KEY=VALUE 파일의 값. 주석 줄 제외, 같은 키가 여럿이면 마지막 값 (docker compose와 같은 규칙). 윈도 줄바꿈 제거
# docker compose처럼 감싼 따옴표를 벗기고, 따옴표 없는 값은 " #" 뒤(주석)를 버림
value_of() {
  local raw
  raw=$(sed -n "s/^[[:space:]]*$2=//p" "$1" | tr -d '\r' | tail -n 1)
  case "$raw" in
    \'*)
      raw=${raw#\'}
      printf '%s' "${raw%%\'*}"
      ;;
    \"*)
      raw=${raw#\"}
      printf '%s' "${raw%%\"*}"
      ;;
    *) printf '%s' "$raw" | sed -E 's/[[:space:]]+#.*$//; s/[[:space:]]+$//' ;;
  esac
}

# docker compose가 값을 바꾸는 경우 실패. 키 이름만 출력
# - 따옴표 없는 값·큰따옴표 값: $이름은 변수로 치환 ($$는 $ 하나)
# - 따옴표 없는 값: " #" 뒤는 주석으로 버림
# - 큰따옴표 값: \n 등 이스케이프 해석
# 작은따옴표 값은 그대로 전달
check_values() {
  local name line key raw
  name=$(basename "$1")
  while IFS= read -r line; do
    key=${line%%=*}
    raw=${line#*=}
    case "$raw" in
      \'*)
        if [[ "${raw#\'}" != *\'* ]]; then
          fail "$name: $key has an unclosed single quote"
        fi
        ;;
      \"*)
        if [[ "$raw" == *[\$\\]* ]]; then
          fail "$name: $key is double-quoted and contains \$ or \\, which docker compose changes. Use single quotes ('...')"
        fi
        ;;
      *)
        if [[ "${raw//\$\$/}" == *\$* ]]; then
          fail "$name: $key contains \$, which docker compose replaces with a variable. Wrap the value in single quotes ('...')"
        elif [[ "$raw" =~ [[:space:]]# ]]; then
          fail "$name: $key contains ' #', and docker compose drops the rest as a comment. Wrap the value in single quotes ('...') or put the comment on its own line"
        fi
        ;;
    esac
  done < <(grep -E "^[[:space:]]*[A-Z_][A-Z0-9_]*=" "$1" | tr -d '\r' | sed -E 's/^[[:space:]]+//')
}

# 다른 계정이 읽을 수 있으면 권한 문자열(ls -l, 예: -rw-r--r--) 출력. 그룹·기타 권한이 없으면 출력 없음
# stat 옵션이 GNU·BSD(macOS)에서 달라 ls 출력의 5~10번째 글자(그룹·기타 rwx)로 판단
loose_permissions() {
  local mode
  # 파일 이름이 아니라 권한 열만 읽으므로 ls 출력 해석 문제(SC2012) 해당 없음
  # shellcheck disable=SC2012
  mode=$(ls -l "$1" | cut -c1-10)
  if [ "$(printf '%s' "$mode" | cut -c5-10)" != "------" ]; then
    printf '%s' "$mode"
  fi
}

# 접속 주소 목록. "https://a.example:443, b" → "a.example", "b"
site_names() {
  printf '%s\n' "$1" | tr ',' '\n' | sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//; s#^[a-z]+://##; s#/.*$##; s#:[0-9]+$##' | grep -v '^$'
}

# 인증서의 주체 대체 이름(SAN). 한 줄에 하나 ("DNS:a.example", "IP Address:10.0.0.1")
# LibreSSL에는 x509 -checkhost·-ext가 없어 -text 출력에서 읽음
cert_names() {
  openssl x509 -in "$1" -noout -text 2>/dev/null |
    grep -A1 'Subject Alternative Name' | tail -n 1 | tr ',' '\n' | sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//'
}

# 인증서가 이름을 포함하는지. 와일드카드(*.example.com)는 한 단계 하위 이름만 포함
covers() {
  local names=$1 name lower entry
  lower=$(printf '%s' "$2" | tr '[:upper:]' '[:lower:]')
  while IFS= read -r entry; do
    entry=$(printf '%s' "$entry" | tr '[:upper:]' '[:lower:]')
    case "$entry" in
      "dns:$lower" | "ip address:$lower") return 0 ;;
      dns:\*.*)
        name=${lower#*.}
        if [ "$lower" != "$name" ] && [ "dns:*.$name" = "$entry" ]; then
          return 0
        fi
        ;;
    esac
  done <<<"$names"
  return 1
}

check_certificate() {
  local cert=$1 key=$2 names=$3 cert_list name
  if [ ! -f "$cert" ] || [ ! -f "$key" ]; then
    fail "Certificate file missing: proxy/certs/$(basename "$cert"), proxy/certs/$(basename "$key")"
    return
  fi
  if ! openssl x509 -in "$cert" -noout 2>/dev/null; then
    fail "$(basename "$cert") is not a readable PEM certificate"
    return
  fi

  if ! openssl x509 -in "$cert" -noout -checkend 0 >/dev/null 2>&1; then
    fail "Certificate expired ($(openssl x509 -in "$cert" -noout -enddate | sed 's/notAfter=//'))"
  elif ! openssl x509 -in "$cert" -noout -checkend $((EXPIRY_WARNING_DAYS * 86400)) >/dev/null 2>&1; then
    warn "Certificate expires within ${EXPIRY_WARNING_DAYS} days ($(openssl x509 -in "$cert" -noout -enddate | sed 's/notAfter=//')). See docs/deploy.md to replace it"
  else
    ok "Certificate valid (expires $(openssl x509 -in "$cert" -noout -enddate | sed 's/notAfter=//'))"
  fi

  local perms
  perms=$(loose_permissions "$key")
  if [ -n "$perms" ]; then
    warn "$(basename "$key") permissions $perms: other accounts on the server can read the private key (chmod 600 proxy/certs/$(basename "$key"))"
  fi

  # 공개키를 비교하여 키와 인증서가 한 쌍인지 확인
  if [ "$(openssl x509 -in "$cert" -noout -pubkey 2>/dev/null)" = "$(openssl pkey -in "$key" -pubout 2>/dev/null)" ] &&
    [ -n "$(openssl pkey -in "$key" -pubout 2>/dev/null)" ]; then
    ok "Private key matches the certificate"
  else
    fail "Private key does not match the certificate (or is passphrase-protected; Caddy needs an unencrypted key)"
  fi

  cert_list=$(cert_names "$cert")
  while IFS= read -r name; do
    if [ "$name" = "localhost" ]; then
      warn "localhost is not in the certificate, so https://localhost on the server shows a certificate warning. Consider removing it from the site address"
    elif covers "$cert_list" "$name"; then
      ok "Certificate covers $name"
    else
      fail "Certificate does not cover $name (certificate names: $(printf '%s' "$cert_list" | tr '\n' ' '))"
    fi
  done <<<"$names"

  # 서버 인증서만 있으면 중간 인증 기관을 모르는 PC에서 신뢰 오류. cert.pem에는 서버 → 중간 순으로 모두 포함
  if [ "$(grep -c 'BEGIN CERTIFICATE' "$cert")" -lt 2 ]; then
    warn "cert.pem holds 1 certificate. If an intermediate CA issued the certificate, append the intermediate certificate after the server certificate"
  else
    ok "cert.pem includes the intermediate certificate ($(grep -c 'BEGIN CERTIFICATE' "$cert") certificates)"
  fi
}

check_proxy_env() {
  local site tls names hsts mode=internal
  section ".env (HTTPS proxy)"
  if [ ! -f "$ENV_FILE" ]; then
    fail ".env missing. Run cp .env.example .env and fill it in"
    return
  fi
  check_values "$ENV_FILE"

  site=$(value_of "$ENV_FILE" SENTINEL_SITE_ADDRESS)
  names=$(site_names "${site:-localhost}")
  if [ "$names" = "localhost" ]; then
    warn "Site address is localhost only. Other PCs cannot connect (set an internal DNS name in SENTINEL_SITE_ADDRESS)"
  else
    ok "Site address: $(printf '%s' "$names" | tr '\n' ' ')"
  fi

  tls=$(value_of "$ENV_FILE" SENTINEL_TLS)
  case "${tls:-internal}" in
    internal)
      ok "Certificate source: Caddy internal CA (each PC must trust the root certificate)"
      ;;
    /certs/*" "/certs/*)
      mode=files
      ok "Certificate source: certificate files"
      local cert_path=${tls%% *} key_path=${tls##* }
      check_certificate "$ROOT/proxy/certs/${cert_path#/certs/}" "$ROOT/proxy/certs/${key_path#/certs/}" "$names"
      ;;
    *@*)
      mode=acme
      warn "Certificate source: Let's Encrypt. Requires public DNS and ports 80 and 443 reachable from the internet"
      ;;
    *)
      fail "SENTINEL_TLS has an invalid format. Use internal, an email address, or \"/certs/cert.pem /certs/key.pem\""
      ;;
  esac

  hsts=$(value_of "$ENV_FILE" SENTINEL_HSTS_MAX_AGE)
  if [ -z "$hsts" ] || [ "$hsts" = "0" ]; then
    ok "HSTS off (SENTINEL_HSTS_MAX_AGE=0)"
  elif ! [[ "$hsts" =~ ^[0-9]+$ ]]; then
    fail "SENTINEL_HSTS_MAX_AGE must be a number of seconds"
  elif [ "$mode" = internal ]; then
    warn "HSTS with the internal CA: PCs that have not trusted the root certificate cannot bypass the warning and cannot connect"
  else
    ok "HSTS on (max-age=$hsts)"
  fi
}

check_app_env() {
  local key value db_host bytes web_host names sslmode
  section "sentinel.env (api)"
  if [ ! -f "$APP_ENV_FILE" ]; then
    fail "sentinel.env missing. Run cp sentinel.env.example sentinel.env and fill it in"
    return
  fi
  check_values "$APP_ENV_FILE"

  # 편집기·스크립트가 파일을 새로 만들면 chmod 600이 풀릴 수 있음
  value=$(loose_permissions "$APP_ENV_FILE")
  if [ -n "$value" ]; then
    warn "sentinel.env permissions $value: other accounts on the server can read the DB password and encryption key (chmod 600 sentinel.env)"
  fi

  for key in DB_URL DB_USERNAME DB_PASSWORD; do
    if [ -z "$(value_of "$APP_ENV_FILE" "$key")" ]; then
      fail "$key is empty"
    fi
  done

  value=$(value_of "$APP_ENV_FILE" DB_URL)
  if [ -n "$value" ]; then
    db_host=$(printf '%s' "$value" | sed -E 's#^jdbc:postgresql://([^:/?]+).*#\1#')
    case "$db_host" in
      localhost | 127.0.0.1)
        fail "DB_URL points to localhost. Inside a container localhost is the container itself; use host.docker.internal or the RDS address"
        ;;
      host.docker.internal)
        ok "DB: PostgreSQL on this server (host.docker.internal)"
        ;;
      *)
        ok "DB: $db_host"
        # PostgreSQL JDBC 기본값 prefer와 allow·disable은 암호화 없이 접속할 수 있음. 모르는 값이면 api가 기동하지 않음
        sslmode=$(printf '%s' "$value" | sed -nE 's/.*[?&]sslmode=([^&]*).*/\1/p')
        case "$sslmode" in
          require | verify-ca | verify-full) ok "DB connection encrypted (sslmode=$sslmode)" ;;
          "") warn "DB_URL has no sslmode. sslmode=require is recommended for RDS (encrypts the DB password and data in transit)" ;;
          disable | allow | prefer) warn "DB_URL has sslmode=$sslmode, which may connect without encryption. sslmode=require is recommended for RDS" ;;
          *) fail "DB_URL has an unknown sslmode ($sslmode). Use require, verify-ca or verify-full" ;;
        esac
        ;;
    esac
  fi

  value=$(value_of "$APP_ENV_FILE" SENTINEL_SECRET_KEY)
  if [ -z "$value" ]; then
    warn "SENTINEL_SECRET_KEY missing. Environment secrets (tokens, API keys) and project webhooks cannot be registered"
  else
    bytes=$(printf '%s' "$value" | openssl base64 -d -A 2>/dev/null | wc -c | tr -d ' ')
    case "$bytes" in
      16 | 24 | 32) ok "SENTINEL_SECRET_KEY format OK (${bytes} bytes). If lost, stored secrets and project webhooks cannot be recovered; keep it somewhere safe" ;;
      *) fail "SENTINEL_SECRET_KEY must be Base64 for 16, 24 or 32 bytes (generate: openssl rand -base64 32)" ;;
    esac
  fi

  # 키 교체 중에만 존재. 기동 시 이전 키로 저장된 값을 새 키로 다시 암호화
  previous=$(value_of "$APP_ENV_FILE" SENTINEL_SECRET_KEY_PREVIOUS)
  if [ -n "$previous" ]; then
    bytes=$(printf '%s' "$previous" | openssl base64 -d -A 2>/dev/null | wc -c | tr -d ' ')
    if [ -z "$value" ]; then
      fail "Only SENTINEL_SECRET_KEY_PREVIOUS is set. The api needs the new key in SENTINEL_SECRET_KEY to start"
    elif [ "$bytes" != 16 ] && [ "$bytes" != 24 ] && [ "$bytes" != 32 ]; then
      fail "SENTINEL_SECRET_KEY_PREVIOUS must be Base64 for 16, 24 or 32 bytes (the key before rotation, unchanged)"
    elif [ "$previous" = "$value" ]; then
      warn "SENTINEL_SECRET_KEY_PREVIOUS equals SENTINEL_SECRET_KEY. Nothing to rotate; remove it"
    else
      warn "SENTINEL_SECRET_KEY_PREVIOUS is set (key rotation in progress). Remove it from sentinel.env after the startup log confirms re-encryption"
    fi
  fi

  if [ -n "$(value_of "$APP_ENV_FILE" SENTINEL_ADMIN_PASSWORD)" ]; then
    warn "SENTINEL_ADMIN_PASSWORD is set. Log in as the initial administrator, change the password, then remove it from sentinel.env"
  elif [ -z "$(value_of "$APP_ENV_FILE" SENTINEL_ADMIN_EMAIL)" ]; then
    warn "No initial administrator configured. On a first deployment with no users, nobody can log in"
  fi

  value=$(value_of "$APP_ENV_FILE" SENTINEL_WEB_URL)
  names=$(site_names "$( [ -f "$ENV_FILE" ] && value_of "$ENV_FILE" SENTINEL_SITE_ADDRESS)")
  if [ -z "$value" ]; then
    warn "SENTINEL_WEB_URL missing. Failure alerts will not include result links"
  else
    web_host=$(site_names "$value")
    if printf '%s\n' "$names" | grep -qxF "$web_host"; then
      ok "Alert link address matches the site address ($web_host)"
    else
      warn "SENTINEL_WEB_URL ($web_host) is not a site address. Use the https://<site address> that users open"
    fi
  fi

  if [ -z "$(value_of "$APP_ENV_FILE" SENTINEL_WEBHOOK_URL)" ]; then
    warn "SENTINEL_WEBHOOK_URL missing. Failure alerts for projects without a project webhook, DB connection failures, monitoring gaps and weekly digests are only logged"
  fi

  if [ -z "$(value_of "$APP_ENV_FILE" SENTINEL_HEARTBEAT_URL)" ]; then
    warn "SENTINEL_HEARTBEAT_URL missing. If Sentinel stops, nobody notices until it starts again"
  fi
}

check_proxy_env
check_app_env

printf '\nFailures: %d, warnings: %d\n' "$failures" "$warnings"
[ "$failures" -eq 0 ]
