#!/usr/bin/env bash
# scripts/sentinel-run.sh 테스트. Sentinel API를 흉내 내는 작은 HTTP 서버(python3)에 시나리오별로 실행
#
#   bash scripts/sentinel-run.test.sh
#
# 테스트를 실행한 bash로 스크립트도 실행 (macOS 기본 bash 3.2 호환 확인: /bin/bash scripts/sentinel-run.test.sh)
set -uo pipefail

SCRIPT="$(cd "$(dirname "$0")" && pwd)/sentinel-run.sh"
WORK=$(mktemp -d)
TOKEN="snt_token-value-must-not-appear"

cat >"$WORK/server.py" <<'PY'
import json, os, sys
from http.server import BaseHTTPRequestHandler, HTTPServer

work = sys.argv[1]
state = {"polls": 0}

def scenario():
    with open(os.path.join(work, "scenario")) as f:
        return f.read().strip()

def run(status, results):
    passed = sum(1 for r in results if r["verdict"] == "PASSED")
    failed = sum(1 for r in results if r["verdict"] == "FAILED")
    errors = sum(1 for r in results if r["verdict"] == "ERROR")
    return {"id": 7, "status": status, "plannedCount": 2, "results": results,
            "summary": {"total": len(results), "passed": passed, "failed": failed, "errors": errors}}

PASS = {"testCase": {"id": 1, "name": "Get order"}, "verdict": "PASSED", "errorReason": None, "assertionResults": []}
FAIL = {"testCase": {"id": 1, "name": "Approve payment"}, "verdict": "FAILED", "errorReason": None,
        "assertionResults": [{"description": "status in [2xx]", "passed": False}]}
ERROR = {"testCase": {"id": 1, "name": "Refund"}, "verdict": "ERROR", "errorReason": "TIMEOUT", "assertionResults": []}

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def reply(self, code, payload):
        body = json.dumps(payload).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def record(self):
        with open(os.path.join(work, "requests"), "a") as f:
            f.write(f"{self.command} {self.path} {self.headers.get('Authorization')}\n")

    def do_POST(self):
        self.record()
        data = self.rfile.read(int(self.headers.get("Content-Length", 0))).decode()
        with open(os.path.join(work, "body"), "w") as f:
            f.write(data)
        state["polls"] = 0
        name = scenario()
        if name == "unauthorized":
            return self.reply(401, {"detail": "authentication required"})
        if name == "busy":
            return self.reply(429, {"detail": "4 manual runs are already in progress"})
        self.reply(202, run("RUNNING", []))

    def do_GET(self):
        self.record()
        name = scenario()
        if self.headers.get("Accept") == "application/xml":
            if name == "xml-broken":
                return self.reply(500, {"detail": "boom"})
            body = b'<?xml version="1.0" encoding="UTF-8"?>\n<testsuites name="Sentinel"/>\n'
            self.send_response(200)
            self.send_header("Content-Type", "application/xml")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            return self.wfile.write(body)
        state["polls"] += 1
        if name == "slow" or state["polls"] < 2:
            return self.reply(200, run("RUNNING", [PASS]))
        if name in ("pass", "xml-broken"):
            return self.reply(200, run("COMPLETED", [PASS, PASS]))
        if name == "fail":
            return self.reply(200, run("COMPLETED", [PASS, FAIL, ERROR]))
        if name == "canceled":
            return self.reply(200, run("CANCELED", [PASS]))
        if name == "gone":
            return self.reply(404, {"detail": "test run 7 not found"})

server = HTTPServer(("127.0.0.1", 0), Handler)
with open(os.path.join(work, "port"), "w") as f:
    f.write(str(server.server_address[1]))
server.serve_forever()
PY

python3 "$WORK/server.py" "$WORK" &
SERVER=$!
# wait로 종료 알림(Terminated) 출력 없이 정리
trap 'kill $SERVER 2>/dev/null; wait $SERVER 2>/dev/null; rm -rf "$WORK"' EXIT
for _ in $(seq 50); do
  [[ -s "$WORK/port" ]] && break
  sleep 0.1
done
URL="http://127.0.0.1:$(cat "$WORK/port")"

passed=0
failed=0

# $1 이름, $2 시나리오, $3 기대 종료 코드, $4 출력에 있어야 하는 문자열 (나머지는 스크립트 인자)
check() {
  local name="$1" scenario="$2" expected="$3" needle="$4"
  shift 4
  echo "$scenario" >"$WORK/scenario"
  : >"$WORK/requests"
  local output code
  output=$(SENTINEL_URL="$URL/" SENTINEL_TOKEN="$TOKEN" "$BASH" "$SCRIPT" "$@" 2>&1)
  code=$?
  if [[ "$code" == "$expected" && "$output" == *"$needle"* && "$output" != *"$TOKEN"* ]]; then
    passed=$((passed + 1))
  else
    failed=$((failed + 1))
    printf 'FAIL %s: exit %s (expected %s)\n%s\n' "$name" "$code" "$expected" "$output"
  fi
}

check "exits 0 when all pass" pass 0 "Passed: 2 cases, 2 passed, 0 failed, 0 errors" --suite 3 --environment 2 --interval 0
check "prints the run URL" pass 0 "Started run #7: $URL/runs/7" --suite 3 --environment 2 --interval 0
check "prints progress" pass 0 "Running: 1/2 cases" --suite 3 --environment 2 --interval 0
check "exits 1 and lists failing cases" fail 1 "  - Approve payment: status in [2xx]" --suite 3 --environment 2 --interval 0
check "prints the error reason" fail 1 "  - Refund: TIMEOUT" --suite 3 --environment 2 --interval 0
check "exits 2 for a canceled run" canceled 2 "The run did not finish (CANCELED)" --suite 3 --environment 2 --interval 0
check "exits 2 when the token is rejected" unauthorized 2 "The token is missing, expired or revoked" --suite 3 --environment 2
check "exits 2 at the concurrent run limit" busy 2 "Too many manual runs in progress" --suite 3 --environment 2
check "exits 2 when the result cannot be read" gone 2 "HTTP 404 test run 7 not found" --suite 3 --environment 2 --interval 0
check "exits 2 on timeout" slow 2 "Timed out after 1s" --suite 3 --environment 2 --timeout 1 --interval 0.2
check "rejects non-numeric IDs" pass 2 "Specify numeric IDs" --suite abc --environment 2

# 연결 실패 (닫힌 포트)
output=$(SENTINEL_URL="http://127.0.0.1:9" SENTINEL_TOKEN="$TOKEN" "$BASH" "$SCRIPT" --suite 3 --environment 2 2>&1)
code=$?
if [[ "$code" == 2 && "$output" == *"Could not connect to Sentinel"* ]]; then
  passed=$((passed + 1))
else
  failed=$((failed + 1))
  echo "FAIL connection failure: $output"
fi

# --junit: 끝난 실행은 통과·실패 모두 저장, 시간 초과는 저장하지 않음, 받지 못하면 경고만 (종료 코드는 실행 결과)
junit_check() {
  local name="$1" scenario="$2" expected="$3" saved="$4" needle="$5"
  shift 5
  echo "$scenario" >"$WORK/scenario"
  rm -f "$WORK/report.xml"
  local output code
  output=$(SENTINEL_URL="$URL" SENTINEL_TOKEN="$TOKEN" "$BASH" "$SCRIPT" --suite 3 --environment 2 --interval 0 \
    --junit "$WORK/report.xml" "$@" 2>&1)
  code=$?
  local exists=no
  if [[ -s "$WORK/report.xml" ]] && grep -q '<testsuites name="Sentinel"/>' "$WORK/report.xml"; then
    exists=yes
  fi
  if [[ "$code" == "$expected" && "$exists" == "$saved" && "$output" == *"$needle"* ]]; then
    passed=$((passed + 1))
  else
    failed=$((failed + 1))
    printf 'FAIL %s: exit %s (expected %s), saved %s (expected %s)\n%s\n' "$name" "$code" "$expected" "$exists" \
      "$saved" "$output"
  fi
}

junit_check "saves a passed run" pass 0 yes "JUnit XML: $WORK/report.xml"
junit_check "saves a failed run" fail 1 yes "JUnit XML: $WORK/report.xml"
junit_check "saves a canceled run" canceled 2 yes "JUnit XML: $WORK/report.xml"
junit_check "does not save on timeout" slow 2 no "Timed out" --timeout 1 --interval 0.2
junit_check "only warns when the XML cannot be downloaded" xml-broken 0 no "Warning: could not download the JUnit XML: HTTP 500"

# 사용법은 맨 위 주석만
usage=$("$BASH" "$SCRIPT" --help 2>&1)
if [[ "$usage" == *"Exit codes"* && "$usage" != *"set -euo"* ]]; then
  passed=$((passed + 1))
else
  failed=$((failed + 1))
  echo "FAIL usage: $usage"
fi

# 요청 내용: 본문, 토큰 헤더 (주소 끝의 /는 제거)
echo pass >"$WORK/scenario"
: >"$WORK/requests"
SENTINEL_URL="$URL/" SENTINEL_TOKEN="$TOKEN" "$BASH" "$SCRIPT" --suite 3 --environment 2 --interval 0 >/dev/null 2>&1
if [[ "$(cat "$WORK/body")" == '{"suiteId": 3, "environmentId": 2}' ]] &&
  grep -q "^POST /api/runs Bearer $TOKEN$" "$WORK/requests" &&
  grep -q "^GET /api/runs/7 Bearer $TOKEN$" "$WORK/requests"; then
  passed=$((passed + 1))
else
  failed=$((failed + 1))
  echo "FAIL request contents"
  cat "$WORK/body" "$WORK/requests"
fi

# 환경변수 없음
if SENTINEL_URL="" SENTINEL_TOKEN="" "$BASH" "$SCRIPT" --suite 3 --environment 2 >/dev/null 2>&1; then
  failed=$((failed + 1))
  echo "FAIL succeeded without environment variables"
else
  passed=$((passed + 1))
fi

echo "passed=$passed failed=$failed"
[[ "$failed" == 0 ]]
