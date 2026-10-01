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

PASS = {"testCaseName": "주문 조회", "verdict": "PASSED", "errorReason": None, "assertionResults": []}
FAIL = {"testCaseName": "결제 승인", "verdict": "FAILED", "errorReason": None,
        "assertionResults": [{"description": "status in [2xx]", "passed": False}]}
ERROR = {"testCaseName": "환불", "verdict": "ERROR", "errorReason": "TIMEOUT", "assertionResults": []}

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
        state["polls"] += 1
        name = scenario()
        if name == "slow" or state["polls"] < 2:
            return self.reply(200, run("RUNNING", [PASS]))
        if name == "pass":
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

check "통과하면 0" pass 0 "통과: 전체 2건, 통과 2, 실패 0, 오류 0" --suite 3 --environment 2 --interval 0
check "시작 주소 안내" pass 0 "실행 #7 시작: $URL/runs/7" --suite 3 --environment 2 --interval 0
check "진행 상황" pass 0 "진행 중: 1/2건" --suite 3 --environment 2 --interval 0
check "실패하면 1과 케이스 목록" fail 1 "  - 결제 승인: status in [2xx]" --suite 3 --environment 2 --interval 0
check "오류 사유" fail 1 "  - 환불: TIMEOUT" --suite 3 --environment 2 --interval 0
check "중지된 실행은 2" canceled 2 "실행이 끝나지 않았습니다 (CANCELED)" --suite 3 --environment 2 --interval 0
check "토큰 거부는 2" unauthorized 2 "토큰이 없거나 만료·폐기되었습니다" --suite 3 --environment 2
check "동시 실행 한도는 2" busy 2 "진행 중인 수동 실행이 많습니다" --suite 3 --environment 2
check "결과 조회 실패는 2" gone 2 "HTTP 404 test run 7 not found" --suite 3 --environment 2 --interval 0
check "시간 초과는 2" slow 2 "시간 초과 (1초)" --suite 3 --environment 2 --timeout 1 --interval 0.2
check "숫자가 아닌 ID" pass 2 "숫자 ID를 지정해 주세요" --suite abc --environment 2

# 연결 실패 (닫힌 포트)
output=$(SENTINEL_URL="http://127.0.0.1:9" SENTINEL_TOKEN="$TOKEN" "$BASH" "$SCRIPT" --suite 3 --environment 2 2>&1)
code=$?
if [[ "$code" == 2 && "$output" == *"Sentinel에 연결하지 못했습니다"* ]]; then
  passed=$((passed + 1))
else
  failed=$((failed + 1))
  echo "FAIL 연결 실패: $output"
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
  echo "FAIL 요청 내용"
  cat "$WORK/body" "$WORK/requests"
fi

# 환경변수 없음
if SENTINEL_URL="" SENTINEL_TOKEN="" "$BASH" "$SCRIPT" --suite 3 --environment 2 >/dev/null 2>&1; then
  failed=$((failed + 1))
  echo "FAIL 환경변수 없이 성공"
else
  passed=$((passed + 1))
fi

echo "passed=$passed failed=$failed"
[[ "$failed" == 0 ]]
