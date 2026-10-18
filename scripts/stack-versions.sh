#!/usr/bin/env bash
# Compares the stack version tables in the READMEs with the versions actually resolved
# (web/package-lock.json, Gradle dependencies, Gradle wrapper). Run before a release
# (release section of docs/branch-convention.md), not on every PR: dependency update PRs
# cannot change the READMEs, so a check there would always fail.
# The rows to check are listed in scripts/stack-versions.list.
#
#   scripts/stack-versions.sh          List the versions that differ
#   scripts/stack-versions.sh --fix    Rewrite them in the READMEs, then list what changed
#
# Needs jq, and Java 25 for Gradle (JAVA_HOME), unless STACK_GRADLE_DEPENDENCIES points to
# saved "gradlew dependencies" output.
#
# Exit codes
#   0  Every listed version matches (or, with --fix, now matches)
#   1  A version differs, or a listed row or package was not found
#   2  Wrong usage, a missing tool or file, or Gradle failed
set -euo pipefail

ROOT="${SENTINEL_ROOT:-$(cd "$(dirname "$0")/.." && pwd)}"

usage() {
  # 맨 위 주석 블록(사용법)만 출력
  awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"
  exit 2
}

fix=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --fix) fix=1; shift ;;
    -h | --help) usage ;;
    *) echo "Unknown argument: $1" >&2; usage ;;
  esac
done

if ! command -v jq > /dev/null; then
  echo "jq is required" >&2
  exit 2
fi

# 확인할 문서의 버전 목록 (형식은 파일 첫머리)
LIST="$ROOT/scripts/stack-versions.list"

for file in scripts/stack-versions.list README.md api/README.md web/README.md web/package-lock.json api/gradle/wrapper/gradle-wrapper.properties; do
  if [[ ! -f "$ROOT/$file" ]]; then
    echo "$file not found" >&2
    exit 2
  fi
done

# Gradle 해석 결과: 앱 실행·테스트 실행·애너테이션 처리(Lombok) 클래스패스
deps_file=$(mktemp)
trap 'rm -f "$deps_file"' EXIT
if [[ -n "${STACK_GRADLE_DEPENDENCIES:-}" ]]; then
  cp "$STACK_GRADLE_DEPENDENCIES" "$deps_file"
else
  for configuration in runtimeClasspath testRuntimeClasspath annotationProcessor; do
    if ! "$ROOT/api/gradlew" -q -p "$ROOT/api" dependencies --configuration "$configuration" >> "$deps_file" 2>&1; then
      echo "Gradle failed (is JAVA_HOME set to Java 25?):" >&2
      tail -n 5 "$deps_file" >&2
      exit 2
    fi
  done
fi

# 그룹:아티팩트의 해석된 버전. "a:b:1.0", "a:b:1.0 -> 1.1", "a:b -> 1.1" (BOM 관리) 중 처음 나온 것
maven_version() {
  awk -v want="$1" '
    {
      line = $0
      sub(/^[-+\\| ]*--- /, "", line)
      if (line == $0) next
      count = split(line, words, " ")
      n = split(words[1], parts, ":")
      if (parts[1] ":" parts[2] != want) next
      version = (n >= 3) ? parts[3] : ""
      for (i = 2; i < count; i++) if (words[i] == "->") version = words[i + 1]
      if (version != "") { print version; exit }
    }' "$deps_file"
}

actual_version() {
  local source="$1"
  case "$source" in
    npm:*)
      jq -r --arg key "node_modules/${source#npm:}" '.packages[$key].version // empty' "$ROOT/web/package-lock.json"
      ;;
    maven:*)
      maven_version "${source#maven:}"
      ;;
    gradle)
      sed -nE 's/^distributionUrl=.*gradle-([0-9.]+)-(bin|all)\.zip$/\1/p' "$ROOT/api/gradle/wrapper/gradle-wrapper.properties"
      ;;
  esac
}

# 문서 표에서 행 이름 셀 바로 다음 셀의 버전. 출력: 줄 번호, 줄 안 위치(1부터), 버전
documented_version() {
  awk -F '|' -v label="$2" -v anchor="$3" '
    /^\|/ {
      for (i = 2; i < NF; i++) {
        cell = $i
        gsub(/^ +| +$/, "", cell)
        if (cell != label) continue
        # 다음 셀의 줄 안 시작 위치
        offset = 1
        for (j = 1; j <= i; j++) offset += length($j) + 1
        rest = $(i + 1)
        start = 1
        if (anchor != "") {
          start = index(rest, anchor)
          if (start == 0) exit
          start += length(anchor)
        }
        if (match(substr(rest, start), /[0-9]+(\.[0-9]+)+(\.Final)?/)) {
          print NR, offset + start - 1 + RSTART - 1, substr(rest, start + RSTART - 1, RLENGTH)
        }
        exit
      }
    }' "$ROOT/$1"
}

# 줄 번호·위치의 옛 버전을 새 버전으로 (같은 크기가 아니어도 됨)
replace_version() {
  local file="$1" line="$2" column="$3" old="$4" new="$5" tmp
  tmp=$(mktemp)
  awk -v line="$line" -v column="$column" -v old="$old" -v new="$new" '
    NR == line && substr($0, column, length(old)) == old {
      $0 = substr($0, 1, column - 1) new substr($0, column + length(old))
    }
    { print }' "$ROOT/$file" > "$tmp"
  cat "$tmp" > "$ROOT/$file"
  rm -f "$tmp"
}

checked=0
differ=0
fixed=0
missing=0
while IFS='|' read -r file label anchor source; do
  [[ -z "$file" || "$file" == \#* ]] && continue
  checked=$((checked + 1))
  what="$file: $label"
  [[ -n "$anchor" ]] && what+=" (${anchor% })"
  actual=$(actual_version "$source")
  if [[ -z "$actual" ]]; then
    echo "NOT FOUND  $what: no resolved version for $source"
    missing=$((missing + 1))
    continue
  fi
  read -r line column documented < <(documented_version "$file" "$label" "$anchor") || true
  if [[ -z "${documented:-}" ]]; then
    echo "NOT FOUND  $what: no version in the table row"
    missing=$((missing + 1))
    continue
  fi
  if [[ "$documented" != "$actual" ]]; then
    if [[ "$fix" == 1 ]]; then
      replace_version "$file" "$line" "$column" "$documented" "$actual"
      echo "FIXED      $what: $documented -> $actual"
      fixed=$((fixed + 1))
    else
      echo "DIFFERS    $what: $documented -> $actual"
      differ=$((differ + 1))
    fi
  fi
  unset line column documented
done < "$LIST"

echo "Checked $checked versions: $differ differ, $fixed fixed, $missing not found"
if [[ "$differ" -gt 0 || "$missing" -gt 0 ]]; then
  exit 1
fi
