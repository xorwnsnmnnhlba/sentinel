#!/usr/bin/env bash
# Sentinel 스위트를 실행하고 끝날 때까지 기다림. 배포 파이프라인의 확인 단계용 (api/README.md "API 토큰")
#
#   SENTINEL_URL=https://sentinel.example.com SENTINEL_TOKEN=snt_... \
#     scripts/sentinel-run.sh --suite 3 --environment 2 [--timeout 600] [--interval 5] [--junit sentinel.xml]
#
# --junit  끝난 실행의 결과를 JUnit XML로 저장 (CI 도구의 테스트 결과 화면용). 통과·실패·중지 모두 저장, 시간 초과는 저장하지 않음
#
# 종료 코드
#   0  모든 케이스 통과
#   1  판정 실패·오류가 있음 (실패한 케이스 목록 출력)
#   2  실행하지 못함 (인증·권한·없는 스위트·동시 실행 한도 등), 중지·중단된 실행, 시간 초과
#
# 환경변수
#   SENTINEL_URL      Sentinel 주소 (HTTPS 앞단). 끝의 / 없이
#   SENTINEL_TOKEN    API 토큰 (화면의 "API 토큰"에서 발급). 출력하지 않음
#   SENTINEL_CA_CERT  사내 인증 기관(Caddy internal) 루트 인증서 경로. 공인 인증서면 생략
#
# 필요: bash, curl, jq (GitHub Actions 러너에 기본 설치)
set -euo pipefail

usage() {
  # 맨 위 주석 블록(사용법)만 출력
  awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"
  exit 2
}

suite=""
environment=""
timeout=600
interval=5
junit=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --suite) suite="${2:-}"; shift 2 ;;
    --environment) environment="${2:-}"; shift 2 ;;
    --timeout) timeout="${2:-}"; shift 2 ;;
    --interval) interval="${2:-}"; shift 2 ;;
    --junit) junit="${2:-}"; shift 2 ;;
    -h | --help) usage ;;
    *) echo "알 수 없는 인자: $1" >&2; usage ;;
  esac
done

is_number() {
  [[ "$1" =~ ^[0-9]+$ ]]
}

if ! is_number "$suite" || ! is_number "$environment"; then
  echo "--suite와 --environment에 숫자 ID를 지정해 주세요 (스위트 화면 주소의 번호, 환경 ID)" >&2
  exit 2
fi
if ! is_number "$timeout" || ! [[ "$interval" =~ ^[0-9]+(\.[0-9]+)?$ ]]; then
  echo "--timeout은 초 단위 정수, --interval은 초 단위 숫자로 지정해 주세요" >&2
  exit 2
fi
if [[ -z "${SENTINEL_URL:-}" || -z "${SENTINEL_TOKEN:-}" ]]; then
  echo "SENTINEL_URL과 SENTINEL_TOKEN 환경변수가 필요합니다" >&2
  exit 2
fi
for tool in curl jq; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "$tool이 필요합니다" >&2
    exit 2
  fi
done

base="${SENTINEL_URL%/}"
curl_options=(--silent --show-error --max-time 30)
if [[ -n "${SENTINEL_CA_CERT:-}" ]]; then
  curl_options+=(--cacert "$SENTINEL_CA_CERT")
fi

# 요청 결과는 status·body 전역 변수로. 토큰은 명령줄 인자 대신 표준 입력의 설정으로 넘겨 프로세스 목록에 남기지 않음
status=""
body=""
request() {
  local method="$1" path="$2" data="${3:-}"
  local response
  local extra=()
  if [[ -n "$data" ]]; then
    extra=(-H "Content-Type: application/json" --data "$data")
  fi
  if ! response=$(printf 'header = "Authorization: Bearer %s"\n' "$SENTINEL_TOKEN" |
    curl "${curl_options[@]}" --config - -H "Accept: application/json" -X "$method" ${extra[@]+"${extra[@]}"} \
      -w '\n%{http_code}' "$base$path"); then
    echo "Sentinel에 연결하지 못했습니다: $base" >&2
    exit 2
  fi
  status="${response##*$'\n'}"
  body="${response%$'\n'*}"
}

detail() {
  jq -r '.detail // empty' <<<"$body" 2>/dev/null || true
}

request POST /api/runs "{\"suiteId\": $suite, \"environmentId\": $environment}"
if [[ "$status" != "202" ]]; then
  case "$status" in
    401) reason="토큰이 없거나 만료·폐기되었습니다" ;;
    403) reason="토큰으로 할 수 없는 요청입니다" ;;
    404) reason="스위트 또는 환경이 없습니다" ;;
    429) reason="진행 중인 수동 실행이 많습니다. 잠시 뒤 다시 실행해 주세요" ;;
    *) reason="HTTP $status" ;;
  esac
  echo "실행을 시작하지 못했습니다: $reason $(detail)" >&2
  exit 2
fi

run_id=$(jq -r '.id' <<<"$body")
echo "실행 #$run_id 시작: $base/runs/$run_id"

deadline=$((SECONDS + timeout))
while true; do
  request GET "/api/runs/$run_id"
  if [[ "$status" != "200" ]]; then
    echo "실행 결과를 확인하지 못했습니다: HTTP $status $(detail)" >&2
    exit 2
  fi
  run_status=$(jq -r '.status' <<<"$body")
  if [[ "$run_status" != "RUNNING" ]]; then
    break
  fi
  if ((SECONDS >= deadline)); then
    echo "시간 초과 (${timeout}초). 실행은 서버에서 계속됩니다: $base/runs/$run_id" >&2
    exit 2
  fi
  echo "진행 중: $(jq -r '"\(.results | length)/\(.plannedCount // "?")건"' <<<"$body")"
  sleep "$interval"
done

# 결과를 JUnit XML로 저장. 저장하지 못해도 종료 코드는 실행 결과 기준 (경고만)
save_junit() {
  if [[ -z "$junit" ]]; then
    return 0
  fi
  local code
  if ! code=$(printf 'header = "Authorization: Bearer %s"\n' "$SENTINEL_TOKEN" |
    curl "${curl_options[@]}" --config - -H "Accept: application/xml" -o "$junit" -w '%{http_code}' \
      "$base/api/runs/$run_id"); then
    echo "경고: JUnit XML을 받지 못했습니다" >&2
    return 0
  fi
  if [[ "$code" != "200" ]]; then
    rm -f "$junit"
    echo "경고: JUnit XML을 받지 못했습니다: HTTP $code" >&2
    return 0
  fi
  echo "JUnit XML: $junit"
}

save_junit

summary=$(jq -r '.summary | "전체 \(.total)건, 통과 \(.passed), 실패 \(.failed), 오류 \(.errors)"' <<<"$body")
failing=$(jq -r '.summary.failed + .summary.errors' <<<"$body")

case "$run_status" in
  COMPLETED)
    if [[ "$failing" == "0" ]]; then
      echo "통과: $summary"
      exit 0
    fi
    echo "실패: $summary" >&2
    jq -r '.results[] | select(.verdict != "PASSED")
      | "  - \(.testCase.name): \(.errorReason // ([.assertionResults[] | select(.passed | not) | .description] | join(", ")))"' \
      <<<"$body" >&2
    echo "결과: $base/runs/$run_id" >&2
    exit 1
    ;;
  *)
    echo "실행이 끝나지 않았습니다 ($run_status): $summary" >&2
    echo "결과: $base/runs/$run_id" >&2
    exit 2
    ;;
esac
