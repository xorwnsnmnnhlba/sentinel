#!/usr/bin/env bash
# 화면이 쓰는 API 응답 시간 측정 (성능 점검용, scripts/perf/README.md). E2E 스택 + seed-history.sql 데이터 전제
#
#   scripts/perf/measure.sh
#
# 요청마다 5회 호출하여 중앙값(ms) 출력. 로그인은 E2E 스택의 테스트 계정
set -euo pipefail

API="${E2E_API_URL:-http://localhost:18080}"
COMPOSE=(docker compose -f "$(cd "$(dirname "$0")/../.." && pwd)/docker-compose.e2e.yml")
REPEAT=5

sql() {
  "${COMPOSE[@]}" exec -T e2e-postgres psql -U sentinel -d sentinel -At -c "SET search_path = sentinel; $1" | tail -n 1
}

token=$(curl -sf -X POST "$API/api/sessions" -H 'Content-Type: application/json' \
  -d '{"email":"admin@sentinel.test","password":"e2e-admin-password"}' | sed -E 's/.*"token":"([^"]+)".*/\1/')

suite=$(sql "SELECT min(id) FROM test_suite WHERE name = 'perf-suite-1'")
environment=$(sql "SELECT e.id FROM environment e JOIN test_suite s ON s.project_id = e.project_id WHERE s.id = $suite")
slowest_case=$(sql "SELECT min(id) FROM test_case WHERE suite_id = $suite")
latest_run=$(sql "SELECT max(id) FROM test_run WHERE suite_id = $suite AND test_case_id IS NULL")
# 오래된 실행 (이력이 짧으면 가장 오래된 실행)
old_run=$(sql "SELECT coalesce(max(id) FILTER (WHERE started_at < now() - interval '180 days'), min(id)) FROM test_run WHERE suite_id = $suite AND test_case_id IS NULL")
today=$(date +%F)
week_ago=$(date -v-6d +%F 2>/dev/null || date -d '6 days ago' +%F)
month_ago=$(date -v-29d +%F 2>/dev/null || date -d '29 days ago' +%F)
quarter_ago=$(date -v-89d +%F 2>/dev/null || date -d '89 days ago' +%F)

measure() {
  local label=$1 path=$2 times=()
  for _ in $(seq "$REPEAT"); do
    times+=("$(curl -s -o /dev/null -w '%{http_code} %{time_total}' -H "Authorization: Bearer $token" "$API$path")")
  done
  local status=${times[0]%% *}
  local median
  median=$(printf '%s\n' "${times[@]}" | awk '{print $2 * 1000}' | sort -n | awk '{a[NR]=$1} END {printf "%d", a[int((NR+1)/2)]}')
  printf '%4s %6s ms  %s  %s\n' "$status" "$median" "$label" "$path"
}

echo "suite=$suite environment=$environment case=$slowest_case run=$latest_run old_run=$old_run"
measure "Dashboard suite summaries (7 days)" "/api/suite-summaries"
measure "Dashboard suite summaries (30 days)" "/api/suite-summaries?from=$month_ago&to=$today"
measure "Dashboard response time changes (7 days)" "/api/response-time-changes?limit=5"
measure "Dashboard recent failures (7 days)" "/api/runs?failing=true&size=5&from=$week_ago&to=$today"
measure "Run history first page" "/api/runs?page=0&size=20"
measure "Run history last page" "/api/runs?page=6000&size=20"
measure "Run history by suite" "/api/runs?suiteId=$suite&page=0&size=20"
measure "Run history failures only" "/api/runs?failing=true&page=0&size=20"
measure "Run history period (30 days)" "/api/runs?from=$month_ago&to=$today&page=0&size=20"
measure "Run detail (with schema drift)" "/api/runs/$latest_run"
measure "Old run detail (180 days ago)" "/api/runs/$old_run"
measure "Suite response time summaries (7 days)" "/api/response-time-summaries?suiteId=$suite&environmentId=$environment"
measure "Suite response time summaries (90 days)" "/api/response-time-summaries?suiteId=$suite&environmentId=$environment&from=$quarter_ago&to=$today"
measure "Case response time trend (90 days)" "/api/response-time-trends?caseId=$slowest_case&environmentId=$environment&from=$quarter_ago&to=$today"
