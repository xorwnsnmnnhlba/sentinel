#!/usr/bin/env bash
# Prints the release version and checks that it is the same everywhere (release section of docs/branch-convention.md)
#
#   scripts/release-version.sh                Print the version (api/build.gradle)
#   scripts/release-version.sh --check        Fail unless api/build.gradle and web/package.json match
#   scripts/release-version.sh --tag v1.0.0   Also fail unless the tag is "v" + that version
#
# Exit codes
#   0  The versions match
#   1  The versions differ or are not MAJOR.MINOR.PATCH
#   2  Wrong usage or a file is missing
set -euo pipefail

ROOT="${SENTINEL_ROOT:-$(cd "$(dirname "$0")/.." && pwd)}"

usage() {
  # 맨 위 주석 블록(사용법)만 출력
  awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"
  exit 2
}

mode=print
tag=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --check) mode=check; shift ;;
    --tag) mode=check; tag="${2:-}"; [[ -n "$tag" ]] || usage; shift 2 ;;
    -h | --help) usage ;;
    *) echo "Unknown argument: $1" >&2; usage ;;
  esac
done

for file in api/build.gradle web/package.json; do
  if [[ ! -f "$ROOT/$file" ]]; then
    echo "$file not found" >&2
    exit 2
  fi
done

# build.gradle의 version = '1.0.0', package.json의 첫 "version" (최상위 키)
api=$(sed -nE "s/^version = '([^']*)'.*/\1/p" "$ROOT/api/build.gradle" | head -n 1)
web=$(sed -nE 's/^  "version": "([^"]*)".*/\1/p' "$ROOT/web/package.json" | head -n 1)

if [[ "$mode" == print ]]; then
  echo "$api"
  exit 0
fi

failed=0
if [[ ! "$api" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "api/build.gradle version '$api' is not MAJOR.MINOR.PATCH" >&2
  failed=1
fi
if [[ "$api" != "$web" ]]; then
  echo "Versions differ: api/build.gradle '$api', web/package.json '$web'" >&2
  failed=1
fi
if [[ -n "$tag" && "$tag" != "v$api" ]]; then
  echo "Tag '$tag' does not match the version in the files (expected v$api)" >&2
  failed=1
fi
if [[ "$failed" -eq 0 ]]; then
  echo "Version $api${tag:+ matches tag $tag}"
fi
exit "$failed"
