#!/usr/bin/env bash
# Runs a Sentinel suite and waits until it finishes. For the verification step of a deployment pipeline
# (see api/README.md)
#
#   SENTINEL_URL=https://sentinel.example.com SENTINEL_TOKEN=snt_... \
#     scripts/sentinel-run.sh --suite 3 --environment 2 [--timeout 600] [--interval 5] [--junit sentinel.xml]
#
# --junit  Save the finished run as JUnit XML (for CI test result views). Saved for passed, failed and
#          canceled runs, not on timeout
#
# Exit codes
#   0  All cases passed
#   1  Some cases failed or errored (the failing cases are listed)
#   2  Could not run (authentication, permission, unknown suite, concurrent run limit, ...),
#      the run was canceled or aborted, or the wait timed out
#
# Environment variables
#   SENTINEL_URL      Sentinel address (https://, HTTPS proxy), without a trailing /. http:// only for localhost
#   SENTINEL_TOKEN    API token (issued at /settings/access-tokens in Sentinel). Never printed
#   SENTINEL_CA_CERT  Root certificate path for the internal CA (Caddy internal). Omit for public certificates
#
# Requires: bash, curl, jq (preinstalled on GitHub Actions runners)
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
    *) echo "Unknown argument: $1" >&2; usage ;;
  esac
done

is_number() {
  [[ "$1" =~ ^[0-9]+$ ]]
}

if ! is_number "$suite" || ! is_number "$environment"; then
  echo "Specify numeric IDs for --suite and --environment (the number in the suite page URL, the environment ID)" >&2
  exit 2
fi
if ! is_number "$timeout" || ! [[ "$interval" =~ ^[0-9]+(\.[0-9]+)?$ ]]; then
  echo "--timeout must be a whole number of seconds and --interval a number of seconds" >&2
  exit 2
fi
if [[ -z "${SENTINEL_URL:-}" || -z "${SENTINEL_TOKEN:-}" ]]; then
  echo "SENTINEL_URL and SENTINEL_TOKEN environment variables are required" >&2
  exit 2
fi
for tool in curl jq; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "$tool is required" >&2
    exit 2
  fi
done

base="${SENTINEL_URL%/}"
# http:// 이면 토큰이 평문으로 전송됨. 같은 PC(localhost)만 허용. 주소에 방식이 없으면 curl이 http로 간주
if [[ ! "$base" =~ ^https:// && ! "$base" =~ ^http://(localhost|127\.0\.0\.1|\[::1\])(:[0-9]+)?$ ]]; then
  echo "SENTINEL_URL must start with https:// (the token would be sent in plain text). http:// is allowed only for localhost" >&2
  exit 2
fi
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
    echo "Could not connect to Sentinel: $base" >&2
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
    401) reason="The token is missing, expired or revoked" ;;
    403) reason="This request is not allowed with an API token" ;;
    404) reason="The suite or environment does not exist" ;;
    429) reason="Too many manual runs in progress. Try again shortly" ;;
    *) reason="HTTP $status" ;;
  esac
  echo "Could not start the run: $reason $(detail)" >&2
  exit 2
fi

run_id=$(jq -r '.id' <<<"$body")
echo "Started run #$run_id: $base/runs/$run_id"

deadline=$((SECONDS + timeout))
while true; do
  request GET "/api/runs/$run_id"
  if [[ "$status" != "200" ]]; then
    echo "Could not check the run: HTTP $status $(detail)" >&2
    exit 2
  fi
  run_status=$(jq -r '.status' <<<"$body")
  if [[ "$run_status" != "RUNNING" ]]; then
    break
  fi
  if ((SECONDS >= deadline)); then
    echo "Timed out after ${timeout}s. The run continues on the server: $base/runs/$run_id" >&2
    exit 2
  fi
  echo "Running: $(jq -r '"\(.results | length)/\(.plannedCount // "?") cases"' <<<"$body")"
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
    echo "Warning: could not download the JUnit XML" >&2
    return 0
  fi
  if [[ "$code" != "200" ]]; then
    rm -f "$junit"
    echo "Warning: could not download the JUnit XML: HTTP $code" >&2
    return 0
  fi
  echo "JUnit XML: $junit"
}

save_junit

summary=$(jq -r '.summary | "\(.total) cases, \(.passed) passed, \(.failed) failed, \(.errors) errors"' <<<"$body")
failing=$(jq -r '.summary.failed + .summary.errors' <<<"$body")

case "$run_status" in
  COMPLETED)
    if [[ "$failing" == "0" ]]; then
      echo "Passed: $summary"
      exit 0
    fi
    echo "Failed: $summary" >&2
    jq -r '.results[] | select(.verdict != "PASSED")
      | "  - \(.testCase.name): \(.errorReason // ([.assertionResults[] | select(.passed | not) | .description] | join(", ")))"' \
      <<<"$body" >&2
    echo "Result: $base/runs/$run_id" >&2
    exit 1
    ;;
  *)
    echo "The run did not finish ($run_status): $summary" >&2
    echo "Result: $base/runs/$run_id" >&2
    exit 2
    ;;
esac
