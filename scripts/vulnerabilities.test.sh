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
    echo "✘ $1"
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
check "수정판 있는 Java 라이브러리 높음 이상은 막음" '[ "$status" -eq 1 ] && contains "높음 이상 취약점 1건" && contains "| Maven | \`org.example:web@1.0.0\` | 9.8 | VULN-org.example:web | 있음 |"'

# 막지 않는 경우: 수정판 없음, 보통 이하, OS 패키지(수정판이 있어도), 다른 패키지에만 수정판
osv_result \
  "$(osv_package Maven org.example:nofix 9.8 "")" \
  "$(osv_package Maven org.example:moderate 6.9 1.0.1)" \
  "$(osv_package Ubuntu:26.04 perl 7.5 5.40.2)" \
  "$(osv_package Go stdlib "" 1.26.8)" >"$WORK/report.json"
run --osv "$WORK/report.json"
check "수정판 없음·보통 이하·OS 패키지·점수 없음은 보고만" '[ "$status" -eq 0 ] && contains "막을 취약점 없음" && contains "- Maven: 2건" && contains "- Ubuntu:26.04: 1건" && contains "- Go: 1건"'
check "다른 패키지의 수정 버전은 이 패키지의 수정판으로 보지 않음" 'contains "보고만" && ! contains "org.example:nofix@1.0.0\` | 9.8"'

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
check "npm 운영 의존성의 수정 가능한 높음·치명은 막음" '[ "$status" -eq 1 ] && contains "높음 이상 취약점 2건" && contains "| npm | \`next (영향 범위 16.0.0 - 16.3.7)\` | critical | https://github.com/advisories/GHSA-next | 있음 |" && contains "\`sharp (영향 범위 <0.35.5)\`"'
check "npm 수정판 없음·보통은 보고만" 'contains "- npm: 2건" && ! contains "\`nofix"'

echo '{"results": []}' >"$WORK/empty-osv.json"
echo '{"vulnerabilities": {}}' >"$WORK/empty-npm.json"
run --osv "$WORK/empty-osv.json" --npm "$WORK/empty-npm.json"
check "취약점이 없으면 통과" '[ "$status" -eq 0 ] && contains "막을 취약점 없음" && contains "- 없음"'

run --osv "$WORK/block.json" --npm "$WORK/empty-npm.json"
check "여러 결과를 합쳐 판정" '[ "$status" -eq 1 ] && contains "높음 이상 취약점 1건"'

run --osv "$WORK/missing.json"
check "결과 파일이 없으면 오류" '[ "$status" -eq 2 ] && contains "결과 파일 없음"'

run --unknown
check "알 수 없는 인자는 오류" '[ "$status" -eq 2 ] && contains "사용법"'

echo "통과 $passed · 실패 $failed"
[ "$failed" -eq 0 ]
