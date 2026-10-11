#!/usr/bin/env bash
# scripts/vulnerabilities.sh 테스트. OSV-Scanner·npm audit 결과 모양의 작은 JSON으로 판정 확인
#
#   bash scripts/vulnerabilities.test.sh
#
# check의 조건은 결과가 나온 뒤 평가하도록 작은따옴표로 전달 (eval)
# shellcheck disable=SC2016
set -uo pipefail

SCRIPT="$(cd "$(dirname "$0")" && pwd)/vulnerabilities.sh"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

passed=0
failed=0

check() {
  if eval "$2"; then
    passed=$((passed + 1))
  else
    failed=$((failed + 1))
    echo "✘ $1 (exit code $status)"
    printf '%s\n' "$output" | sed 's/^/    /'
  fi
}

run() {
  output=$(bash "$SCRIPT" "$@" 2>&1)
  status=$?
}

contains() { printf '%s' "$output" | grep -qF -- "$1"; }

# OSV-Scanner 결과의 패키지 하나. $1 생태계, $2 이름, $3 점수, $4 수정 버전("" 이면 없음)
osv_package() {
  local events='{"introduced": "0"}'
  if [ -n "$4" ]; then
    events="$events, {\"fixed\": \"$4\"}"
  fi
  cat <<JSON
{"package": {"name": "$2", "version": "1.0.0", "ecosystem": "$1"},
 "groups": [{"ids": ["VULN-$2"], "max_severity": "$3"}],
 "vulnerabilities": [{"id": "VULN-$2", "affected": [
   {"package": {"ecosystem": "$1", "name": "other-$2"}, "ranges": [{"events": [{"introduced": "0"}, {"fixed": "9.9.9"}]}]},
   {"package": {"ecosystem": "$1", "name": "$2"}, "ranges": [{"events": [$events]}]}]}]}
JSON
}

osv_result() {
  local packages
  packages=$(IFS=,; echo "$*")
  echo "{\"results\": [{\"source\": {\"path\": \"/app/app.jar\"}, \"packages\": [$packages]}]}"
}

# 막는 경우: 애플리케이션 의존성(Maven)의 높음 이상 + 수정판 있음
osv_result "$(osv_package Maven org.example:web 9.8 7.0.9)" >"$WORK/block.json"
run --osv "$WORK/block.json"
check "blocks a high Java library vulnerability with a fix" '[ "$status" -eq 1 ] && contains "1 high or critical vulnerabilities" && contains "| Maven | \`org.example:web@1.0.0\` | 9.8 | VULN-org.example:web | yes |"'

# 막지 않는 경우: 수정판 없음, 보통 이하, OS 패키지(수정판이 있어도), 다른 패키지에만 수정판
osv_result \
  "$(osv_package Maven org.example:nofix 9.8 "")" \
  "$(osv_package Maven org.example:moderate 6.9 1.0.1)" \
  "$(osv_package Ubuntu:26.04 perl 7.5 5.40.2)" \
  "$(osv_package Go stdlib "" 1.26.8)" >"$WORK/report.json"
run --osv "$WORK/report.json"
check "only reports no fix, moderate, OS packages and missing scores" '[ "$status" -eq 0 ] && contains "No blocking vulnerabilities" && contains "- Maven: 2" && contains "- Ubuntu:26.04: 1" && contains "- Go: 1"'
check "a fix for another package does not count" 'contains "Reported only" && ! contains "org.example:nofix@1.0.0\` | 9.8"'

cat >"$WORK/npm.json" <<'JSON'
{"vulnerabilities": {
  "next": {"name": "next", "severity": "critical", "range": "16.0.0 - 16.3.7",
           "via": [{"url": "https://github.com/advisories/GHSA-next"}], "fixAvailable": {"name": "next", "version": "16.4.0"}},
  "sharp": {"name": "sharp", "severity": "high", "range": "<0.35.5", "via": ["libvips"], "fixAvailable": true},
  "nofix": {"name": "nofix", "severity": "high", "range": "*", "via": [], "fixAvailable": false},
  "minor": {"name": "minor", "severity": "moderate", "range": "*", "via": [], "fixAvailable": true}
}}
JSON
run --npm "$WORK/npm.json"
check "blocks fixable high and critical npm runtime dependencies" '[ "$status" -eq 1 ] && contains "2 high or critical vulnerabilities" && contains "| npm | \`next (affected 16.0.0 - 16.3.7)\` | critical | https://github.com/advisories/GHSA-next | yes |" && contains "\`sharp (affected <0.35.5)\`"'
check "only reports npm without a fix or moderate" 'contains "- npm: 2" && ! contains "\`nofix"'

echo '{"results": []}' >"$WORK/empty-osv.json"
echo '{"vulnerabilities": {}}' >"$WORK/empty-npm.json"
run --osv "$WORK/empty-osv.json" --npm "$WORK/empty-npm.json"
check "passes without vulnerabilities" '[ "$status" -eq 0 ] && contains "No blocking vulnerabilities" && contains "- none"'

run --osv "$WORK/block.json" --npm "$WORK/empty-npm.json"
check "combines several results" '[ "$status" -eq 1 ] && contains "1 high or critical vulnerabilities"'

run --osv "$WORK/missing.json"
check "errors when a result file is missing" '[ "$status" -eq 2 ] && contains "Result file not found"'

run --unknown
check "errors on unknown arguments" '[ "$status" -eq 2 ] && contains "Usage"'

echo "$passed passed, $failed failed"
[ "$failed" -eq 0 ]
