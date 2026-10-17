#!/usr/bin/env bash
# scripts/release-version.sh 테스트. 임시 폴더에 build.gradle·package.json을 만들어 확인
#
#   bash scripts/release-version.test.sh
set -uo pipefail

SCRIPT="$(cd "$(dirname "$0")" && pwd)/release-version.sh"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

passed=0
failed=0

# $1 api 버전, $2 web 버전
setup() {
  mkdir -p "$WORK/api" "$WORK/web"
  printf "plugins {\n}\n\ngroup = 'com.sentinel'\nversion = '%s'\n" "$1" >"$WORK/api/build.gradle"
  printf '{\n  "name": "sentinel-web",\n  "version": "%s",\n  "dependencies": {\n    "x": { "version": "9.9.9" }\n  }\n}\n' "$2" >"$WORK/web/package.json"
}

# $1 설명, $2 기대 종료 코드, $3 출력에 있어야 할 문구, 나머지는 인자
check() {
  local name=$1 expected=$2 needle=$3 output code
  shift 3
  output=$(SENTINEL_ROOT="$WORK" bash "$SCRIPT" "$@" 2>&1)
  code=$?
  if [[ "$code" == "$expected" && "$output" == *"$needle"* ]]; then
    passed=$((passed + 1))
  else
    failed=$((failed + 1))
    printf 'FAIL %s: exit %s (expected %s)\n%s\n' "$name" "$code" "$expected" "$output"
  fi
}

setup 1.0.0 1.0.0
check "prints the api version" 0 "1.0.0"
check "passes when api and web match" 0 "Version 1.0.0" --check
check "passes when the tag matches" 0 "Version 1.0.0 matches tag v1.0.0" --tag v1.0.0
check "fails for a tag without the matching version" 1 "Tag 'v1.0.1' does not match the version in the files (expected v1.0.0)" --tag v1.0.1
check "fails for a tag without the v prefix" 1 "does not match" --tag 1.0.0

setup 1.0.1 1.0.0
check "fails when api and web differ" 1 "Versions differ: api/build.gradle '1.0.1', web/package.json '1.0.0'" --check

setup 1.0.0-SNAPSHOT 1.0.0-SNAPSHOT
check "fails for a non release version" 1 "is not MAJOR.MINOR.PATCH" --check

rm -f "$WORK/web/package.json"
check "errors when a file is missing" 2 "web/package.json not found" --check
check "errors on unknown arguments" 2 "Unknown argument" --nope

echo "$passed passed, $failed failed"
[ "$failed" -eq 0 ]
