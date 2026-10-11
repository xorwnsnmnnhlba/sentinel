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
value_of() {
  sed -n "s/^[[:space:]]*$2=//p" "$1" | tr -d '\r' | tail -n 1
}

# docker --env-file은 따옴표까지 값으로 읽음. 키 이름만 출력
check_quotes() {
  local file=$1 key
  while IFS= read -r key; do
    fail "$(basename "$file")의 $key 값에 따옴표 사용 (따옴표까지 값이 됨)"
  done < <(grep -E "^[[:space:]]*[A-Z_][A-Z0-9_]*=[\"']" "$file" | sed -E 's/^[[:space:]]*([A-Z0-9_]+)=.*/\1/')
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
    fail "인증서 파일 없음: proxy/certs/$(basename "$cert"), proxy/certs/$(basename "$key")"
    return
  fi
  if ! openssl x509 -in "$cert" -noout 2>/dev/null; then
    fail "$(basename "$cert")를 인증서(PEM)로 읽을 수 없음"
    return
  fi

  if ! openssl x509 -in "$cert" -noout -checkend 0 >/dev/null 2>&1; then
    fail "인증서 만료됨 ($(openssl x509 -in "$cert" -noout -enddate | sed 's/notAfter=//'))"
  elif ! openssl x509 -in "$cert" -noout -checkend $((EXPIRY_WARNING_DAYS * 86400)) >/dev/null 2>&1; then
    warn "인증서가 ${EXPIRY_WARNING_DAYS}일 안에 만료 ($(openssl x509 -in "$cert" -noout -enddate | sed 's/notAfter=//')). 교체 절차는 docs/deploy.md"
  else
    ok "인증서 유효 (만료 $(openssl x509 -in "$cert" -noout -enddate | sed 's/notAfter=//'))"
  fi

  local perms
  perms=$(loose_permissions "$key")
  if [ -n "$perms" ]; then
    warn "$(basename "$key") 권한 $perms. 서버의 다른 계정이 개인 키를 읽을 수 있음 (chmod 600 proxy/certs/$(basename "$key"))"
  fi

  # 공개키를 비교하여 키와 인증서가 한 쌍인지 확인
  if [ "$(openssl x509 -in "$cert" -noout -pubkey 2>/dev/null)" = "$(openssl pkey -in "$key" -pubout 2>/dev/null)" ] &&
    [ -n "$(openssl pkey -in "$key" -pubout 2>/dev/null)" ]; then
    ok "개인 키가 인증서와 한 쌍"
  else
    fail "개인 키가 인증서와 짝이 맞지 않음 (또는 암호가 걸린 키. Caddy는 암호 없는 키만 사용)"
  fi

  cert_list=$(cert_names "$cert")
  while IFS= read -r name; do
    if [ "$name" = "localhost" ]; then
      warn "localhost는 보유 인증서에 없어 서버에서 https://localhost 접속 시 인증서 경고. 접속 주소에서 빼는 것을 권장"
    elif covers "$cert_list" "$name"; then
      ok "인증서가 $name 포함"
    else
      fail "인증서가 $name 을(를) 포함하지 않음 (인증서 이름: $(printf '%s' "$cert_list" | tr '\n' ' '))"
    fi
  done <<<"$names"

  # 서버 인증서만 있으면 중간 인증 기관을 모르는 PC에서 신뢰 오류. cert.pem에는 서버 → 중간 순으로 모두 포함
  if [ "$(grep -c 'BEGIN CERTIFICATE' "$cert")" -lt 2 ]; then
    warn "cert.pem에 인증서가 1개. 보유 인증서가 중간 인증 기관을 거치면 서버 인증서 뒤에 중간 인증서를 이어 붙여야 함"
  else
    ok "cert.pem에 중간 인증서 포함 ($(grep -c 'BEGIN CERTIFICATE' "$cert")개)"
  fi
}

check_proxy_env() {
  local site tls names hsts mode=internal
  section ".env (HTTPS 앞단)"
  if [ ! -f "$ENV_FILE" ]; then
    fail ".env 없음. cp .env.example .env 후 작성"
    return
  fi
  check_quotes "$ENV_FILE"

  site=$(value_of "$ENV_FILE" SENTINEL_SITE_ADDRESS)
  names=$(site_names "${site:-localhost}")
  if [ "$names" = "localhost" ]; then
    warn "접속 주소가 localhost뿐. 다른 PC는 접속 불가 (SENTINEL_SITE_ADDRESS에 내부 DNS 이름 지정)"
  else
    ok "접속 주소: $(printf '%s' "$names" | tr '\n' ' ')"
  fi

  tls=$(value_of "$ENV_FILE" SENTINEL_TLS)
  case "${tls:-internal}" in
    internal)
      ok "인증서 방식: Caddy 내부 인증 기관 (PC마다 루트 인증서 신뢰 등록 필요)"
      ;;
    /certs/*" "/certs/*)
      mode=files
      ok "인증서 방식: 보유 인증서 파일"
      local cert_path=${tls%% *} key_path=${tls##* }
      check_certificate "$ROOT/proxy/certs/${cert_path#/certs/}" "$ROOT/proxy/certs/${key_path#/certs/}" "$names"
      ;;
    *@*)
      mode=acme
      warn "인증서 방식: Let's Encrypt. 공개 DNS와 인터넷에서 80·443 접근이 필요"
      ;;
    *)
      fail "SENTINEL_TLS 형식 오류. internal, 이메일 주소, \"/certs/cert.pem /certs/key.pem\" 중 하나"
      ;;
  esac

  hsts=$(value_of "$ENV_FILE" SENTINEL_HSTS_MAX_AGE)
  if [ -z "$hsts" ] || [ "$hsts" = "0" ]; then
    ok "HSTS 미사용 (SENTINEL_HSTS_MAX_AGE=0)"
  elif ! [[ "$hsts" =~ ^[0-9]+$ ]]; then
    fail "SENTINEL_HSTS_MAX_AGE는 초 단위 숫자"
  elif [ "$mode" = internal ]; then
    warn "내부 인증서에 HSTS 사용. 루트 인증서를 등록하지 않은 PC는 경고를 넘길 수 없어 접속 불가"
  else
    ok "HSTS 사용 (max-age=$hsts)"
  fi
}

check_app_env() {
  local key value db_host bytes web_host names
  section "sentinel.env (api)"
  if [ ! -f "$APP_ENV_FILE" ]; then
    fail "sentinel.env 없음. cp sentinel.env.example sentinel.env 후 작성"
    return
  fi
  check_quotes "$APP_ENV_FILE"

  # 편집기·스크립트가 파일을 새로 만들면 chmod 600이 풀릴 수 있음
  value=$(loose_permissions "$APP_ENV_FILE")
  if [ -n "$value" ]; then
    warn "sentinel.env 권한 $value. 서버의 다른 계정이 DB 비밀번호·암호화 키를 읽을 수 있음 (chmod 600 sentinel.env)"
  fi

  for key in DB_URL DB_USERNAME DB_PASSWORD; do
    if [ -z "$(value_of "$APP_ENV_FILE" "$key")" ]; then
      fail "$key 비어 있음"
    fi
  done

  value=$(value_of "$APP_ENV_FILE" DB_URL)
  if [ -n "$value" ]; then
    db_host=$(printf '%s' "$value" | sed -E 's#^jdbc:postgresql://([^:/?]+).*#\1#')
    case "$db_host" in
      localhost | 127.0.0.1)
        fail "DB_URL이 localhost. 컨테이너 안의 localhost는 컨테이너 자신이므로 host.docker.internal 또는 RDS 주소 사용"
        ;;
      host.docker.internal)
        ok "DB: 서버의 PostgreSQL (host.docker.internal)"
        ;;
      *)
        ok "DB: $db_host"
        if ! printf '%s' "$value" | grep -q 'sslmode='; then
          warn "DB_URL에 sslmode 없음. RDS는 sslmode=require 권장 (DB 비밀번호·데이터 암호화 전송)"
        fi
        ;;
    esac
  fi

  value=$(value_of "$APP_ENV_FILE" SENTINEL_SECRET_KEY)
  if [ -z "$value" ]; then
    warn "SENTINEL_SECRET_KEY 없음. 환경 시크릿(토큰·API 키) 등록과 프로젝트별 알림 Webhook 지정 불가"
  else
    bytes=$(printf '%s' "$value" | openssl base64 -d -A 2>/dev/null | wc -c | tr -d ' ')
    case "$bytes" in
      16 | 24 | 32) ok "SENTINEL_SECRET_KEY 형식 정상 (${bytes}바이트). 분실 시 저장된 시크릿·프로젝트 Webhook 복구 불가, 별도 보관" ;;
      *) fail "SENTINEL_SECRET_KEY는 Base64로 16·24·32바이트 (생성: openssl rand -base64 32)" ;;
    esac
  fi

  # 키 교체 중에만 존재. 기동 시 이전 키로 저장된 값을 새 키로 다시 암호화
  previous=$(value_of "$APP_ENV_FILE" SENTINEL_SECRET_KEY_PREVIOUS)
  if [ -n "$previous" ]; then
    bytes=$(printf '%s' "$previous" | openssl base64 -d -A 2>/dev/null | wc -c | tr -d ' ')
    if [ -z "$value" ]; then
      fail "SENTINEL_SECRET_KEY_PREVIOUS만 있음. 새 키(SENTINEL_SECRET_KEY)도 지정해야 api가 기동"
    elif [ "$bytes" != 16 ] && [ "$bytes" != 24 ] && [ "$bytes" != 32 ]; then
      fail "SENTINEL_SECRET_KEY_PREVIOUS는 Base64로 16·24·32바이트 (교체 전 키 그대로)"
    elif [ "$previous" = "$value" ]; then
      warn "SENTINEL_SECRET_KEY_PREVIOUS가 SENTINEL_SECRET_KEY와 같음. 교체할 것이 없으므로 제거"
    else
      warn "SENTINEL_SECRET_KEY_PREVIOUS 설정됨 (키 교체 중). 기동 로그의 재암호화 확인 후 sentinel.env에서 제거"
    fi
  fi

  if [ -n "$(value_of "$APP_ENV_FILE" SENTINEL_ADMIN_PASSWORD)" ]; then
    warn "SENTINEL_ADMIN_PASSWORD 설정됨. 최초 관리자로 로그인해 비밀번호를 바꾼 뒤 sentinel.env에서 제거"
  elif [ -z "$(value_of "$APP_ENV_FILE" SENTINEL_ADMIN_EMAIL)" ]; then
    warn "최초 관리자 계정 미설정. 사용자가 없는 첫 배포라면 로그인할 수 없음"
  fi

  value=$(value_of "$APP_ENV_FILE" SENTINEL_WEB_URL)
  names=$(site_names "$( [ -f "$ENV_FILE" ] && value_of "$ENV_FILE" SENTINEL_SITE_ADDRESS)")
  if [ -z "$value" ]; then
    warn "SENTINEL_WEB_URL 없음. 실패 알림에 결과 링크가 붙지 않음"
  else
    web_host=$(site_names "$value")
    if printf '%s\n' "$names" | grep -qxF "$web_host"; then
      ok "알림 링크 주소가 접속 주소와 일치 ($web_host)"
    else
      warn "SENTINEL_WEB_URL($web_host)이 접속 주소에 없음. 사용자가 여는 https://<접속 주소>로 지정"
    fi
  fi

  if [ -z "$(value_of "$APP_ENV_FILE" SENTINEL_WEBHOOK_URL)" ]; then
    warn "SENTINEL_WEBHOOK_URL 없음. 프로젝트 Webhook이 없는 프로젝트의 실패 알림과 DB 연결 실패·모니터링 공백·주간 요약은 로그로만 기록"
  fi

  if [ -z "$(value_of "$APP_ENV_FILE" SENTINEL_HEARTBEAT_URL)" ]; then
    warn "SENTINEL_HEARTBEAT_URL 없음. Sentinel이 멈추면 다시 켜질 때까지 알 수 없음"
  fi
}

check_proxy_env
check_app_env

printf '\n실패 %d · 경고 %d\n' "$failures" "$warnings"
[ "$failures" -eq 0 ]
