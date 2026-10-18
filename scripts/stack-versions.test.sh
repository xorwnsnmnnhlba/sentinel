#!/usr/bin/env bash
# scripts/stack-versions.sh 테스트. 임시 폴더에 문서·잠금 파일·Gradle 출력을 만들어 확인하고,
# 실제 목록(scripts/stack-versions.list)의 행이 지금 README에 모두 있는지 확인
#
#   bash scripts/stack-versions.test.sh

# README 문구의 백틱(`@tailwindcss/postcss`)은 글자 그대로라 작은따옴표 안에서 펼치지 않음
# shellcheck disable=SC2016
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SCRIPT="$HERE/stack-versions.sh"
REPO="$(cd "$HERE/.." && pwd)"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

passed=0
failed=0

# 문서 3개·잠금 파일·Wrapper·Gradle 출력·목록. $1 web/README.md의 MSW 버전, $2 Tailwind 행
setup() {
  rm -rf "$WORK/root"
  mkdir -p "$WORK/root/scripts" "$WORK/root/api/gradle/wrapper" "$WORK/root/web"
  cat >"$WORK/root/README.md" <<'EOF'
| 모듈 | 구분 | 버전 | 문서 |
|------|------|------|------|
| `api` | Spring Boot | 4.1.1 (Spring Framework 7.0.9) | |
| | Gradle | 9.8.1 | |
EOF
  printf '| 구분 | 버전 |\n|------|------|\n| PostgreSQL JDBC | 42.7.13 |\n| Jackson | 3.1.7 (직접 지정) |\n' >"$WORK/root/api/README.md"
  # 이름이 겹치는 앞 행(MSW 도구)은 MSW 행으로 보지 않아야 함
  printf '| 구분 | 버전 |\n|------|------|\n| MSW 도구 | 0.1.0 |\n| MSW | %s |\n| Tailwind CSS | %s |\n| 아이콘 | lucide-react 1.55.0 |\n' "$1" "$2" >"$WORK/root/web/README.md"
  cat >"$WORK/root/web/package-lock.json" <<'EOF'
{ "packages": {
  "node_modules/msw": { "version": "3.0.3" },
  "node_modules/tailwindcss": { "version": "4.3.3" },
  "node_modules/@tailwindcss/postcss": { "version": "4.10.0" },
  "node_modules/lucide-react": { "version": "1.55.0" }
} }
EOF
  echo 'distributionUrl=https\://services.gradle.org/distributions/gradle-9.8.1-bin.zip' \
    >"$WORK/root/api/gradle/wrapper/gradle-wrapper.properties"
  # BOM이 정한 버전("a:b -> 1.0"), 요청과 다른 해석("a:b:1.0 -> 1.1"), 그대로("a:b:1.0")
  cat >"$WORK/deps.txt" <<'EOF'
runtimeClasspath - Runtime classpath of source set 'main'.
+--- org.springframework.boot:spring-boot-starter-web -> 4.1.1
|    +--- org.springframework.boot:spring-boot:4.1.1
|    |    \--- org.springframework:spring-core:7.0.9 (*)
+--- org.postgresql:postgresql -> 42.7.13
\--- tools.jackson.core:jackson-databind:3.1.5 -> 3.1.7
EOF
  cat >"$WORK/root/scripts/stack-versions.list" <<'EOF'
# 주석 줄은 건너뜀
README.md|Spring Boot||maven:org.springframework.boot:spring-boot
README.md|Spring Boot|Spring Framework |maven:org.springframework:spring-core
README.md|Gradle||gradle
api/README.md|PostgreSQL JDBC||maven:org.postgresql:postgresql
api/README.md|Jackson||maven:tools.jackson.core:jackson-databind
web/README.md|MSW||npm:msw
web/README.md|Tailwind CSS||npm:tailwindcss
web/README.md|Tailwind CSS|`@tailwindcss/postcss` |npm:@tailwindcss/postcss
web/README.md|아이콘|lucide-react |npm:lucide-react
EOF
}

# $1 설명, $2 기대 종료 코드, $3 출력에 있어야 할 문구, 나머지는 인자
check() {
  local name=$1 expected=$2 needle=$3 output code
  shift 3
  output=$(SENTINEL_ROOT="$WORK/root" STACK_GRADLE_DEPENDENCIES="$WORK/deps.txt" bash "$SCRIPT" "$@" 2>&1)
  code=$?
  if [[ "$code" == "$expected" && "$output" == *"$needle"* ]]; then
    passed=$((passed + 1))
  else
    failed=$((failed + 1))
    printf 'FAIL %s: exit %s (expected %s)\n%s\n' "$name" "$code" "$expected" "$output"
  fi
}

# $1 설명, $2 파일, $3 있어야 할 줄
check_line() {
  if grep -qxF -- "$3" "$WORK/root/$2"; then
    passed=$((passed + 1))
  else
    failed=$((failed + 1))
    printf 'FAIL %s: no line %s in %s\n%s\n' "$1" "$3" "$2" "$(cat "$WORK/root/$2")"
  fi
}

setup 3.0.3 '4.3.3 (`@tailwindcss/postcss` 4.10.0)'
check "passes when every version matches" 0 "Checked 9 versions: 0 differ, 0 fixed, 0 not found"

setup 2.15.0 '4.3.3 (`@tailwindcss/postcss` 4.9.1)'
check "lists a version that differs, including one inside parentheses" 1 "DIFFERS    web/README.md: MSW: 2.15.0 -> 3.0.3"
check "names the anchor of a version inside parentheses" 1 'DIFFERS    web/README.md: Tailwind CSS (`@tailwindcss/postcss`): 4.9.1 -> 4.10.0'
check "counts the differences" 1 "Checked 9 versions: 2 differ, 0 fixed, 0 not found"
check "rewrites them with --fix" 0 "Checked 9 versions: 0 differ, 2 fixed, 0 not found" --fix
check_line "keeps the rest of the row when rewriting" web/README.md '| MSW | 3.0.3 |'
check_line "rewrites only the anchored version even when its length changes" web/README.md '| Tailwind CSS | 4.3.3 (`@tailwindcss/postcss` 4.10.0) |'
check "passes after --fix" 0 "0 differ, 0 fixed"

setup 3.0.3 '4.2.0 (`@tailwindcss/postcss` 4.9.1)'
check "rewrites two versions in one row" 0 "2 fixed" --fix
check_line "keeps both rewritten versions in place" web/README.md '| Tailwind CSS | 4.3.3 (`@tailwindcss/postcss` 4.10.0) |'

setup 3.0.3 '4.3.3 (`@tailwindcss/postcss` 4.10.0)'
sed -i.bak 's/lucide-react 1.55.0/lucide-react 1.47.0/' "$WORK/root/web/README.md"
check "rewrites a version after a Korean row name" 0 "1 fixed" --fix
check_line "keeps a row with a Korean name intact" web/README.md '| 아이콘 | lucide-react 1.55.0 |'

setup 3.0.3 '4.3.3 (`@tailwindcss/postcss` 4.10.0)'
sed -i.bak 's/42.7.13/42.7.11/' "$WORK/deps.txt"
check "takes the version Gradle resolved, not the requested one" 1 "DIFFERS    api/README.md: PostgreSQL JDBC: 42.7.13 -> 42.7.11"

setup 3.0.3 '4.3.3 (`@tailwindcss/postcss` 4.10.0)'
echo 'api/README.md|Jackson||maven:org.flywaydb:flyway-core' >>"$WORK/root/scripts/stack-versions.list"
check "reports a coordinate Gradle did not resolve" 1 "NOT FOUND  api/README.md: Jackson: no resolved version for maven:org.flywaydb:flyway-core"

setup 3.0.3 '4.3.3 (`@tailwindcss/postcss` 4.10.0)'
echo 'web/README.md|MSW||npm:nope' >>"$WORK/root/scripts/stack-versions.list"
check "reports a package missing from the lock file" 1 "NOT FOUND  web/README.md: MSW: no resolved version for npm:nope"

setup 3.0.3 '4.3.3 (`@tailwindcss/postcss` 4.10.0)'
echo 'web/README.md|Vite||npm:msw' >>"$WORK/root/scripts/stack-versions.list"
check "reports a row missing from the README" 1 "NOT FOUND  web/README.md: Vite: no version in the table row"

rm -f "$WORK/root/web/package-lock.json"
check "errors when a file is missing" 2 "web/package-lock.json not found"
check "errors on unknown arguments" 2 "Unknown argument" --nope

# 실제 목록: 행이 지금 README에 모두 있어야 함 (README 표를 고치고 목록을 잊은 경우). 버전 차이는 여기서 보지 않음
grep -oE 'maven:[^|]+$' "$REPO/scripts/stack-versions.list" | sed 's/^maven:/+--- /; s/$/:0.0.0/' >"$WORK/real-deps.txt"
output=$(STACK_GRADLE_DEPENDENCIES="$WORK/real-deps.txt" bash "$SCRIPT" 2>&1)
if [[ "$output" == *"Checked "* && "$output" != *"NOT FOUND"* ]]; then
  passed=$((passed + 1))
else
  failed=$((failed + 1))
  printf 'FAIL the real list matches the README rows:\n%s\n' "$(grep 'NOT FOUND\|not found' <<<"$output")"
fi

echo "$passed passed, $failed failed"
[ "$failed" -eq 0 ]
