#!/usr/bin/env bash
# 의존성 취약점 판정. OSV-Scanner 이미지 점검 결과와 npm audit 결과(JSON)를 읽어 Markdown 요약을 출력하고,
# 막아야 할 취약점이 있으면 종료 코드 1 (.github/workflows/security.yml)
#
#   scripts/vulnerabilities.sh [--osv osv-scanner.json]... [--npm npm-audit.json]...
#
# 막는 기준: 애플리케이션 의존성(Java 라이브러리, npm 운영 의존성)의 높음(CVSS 7.0·high) 이상 중 수정 버전이 있는 것
# 그 외(OS 패키지, 수정판 없음, 보통 이하)는 보고만. OS 패키지는 대부분 수정판이 없고 있어도 기본 이미지 갱신으로만 반영됨
#
# jq 프로그램의 $p·$g·$v는 jq 변수이므로 작은따옴표 그대로 전달
# shellcheck disable=SC2016
set -euo pipefail

# 행: 판정(block|report) 생태계 패키지 심각도 ID 수정판
OSV_ROWS='
.results[]?.packages[]? as $p
| $p.groups[]? as $g
| ($g.max_severity | tonumber? // 0) as $score
| ([$p.vulnerabilities[]? | select(.id as $id | $g.ids | index($id))
    | .affected[]? | select(.package.name == $p.package.name)
    | .ranges[]?.events[]? | .fixed // empty] | length > 0) as $fixed
| [ (if (["Maven", "npm"] | index($p.package.ecosystem)) and $score >= 7 and $fixed then "block" else "report" end),
    $p.package.ecosystem, "\($p.package.name)@\($p.package.version)", ($g.max_severity // "-"),
    ($g.ids | join(", ")), (if $fixed then "yes" else "no" end) ]
| @tsv'

NPM_ROWS='
(.vulnerabilities // {}) | to_entries[] | .value as $v
| ($v.fixAvailable != false) as $fixed
| [ (if ($v.severity == "high" or $v.severity == "critical") and $fixed then "block" else "report" end),
    "npm", "\($v.name) (affected \($v.range))", $v.severity,
    ([$v.via[]? | objects | .url // empty] | unique | join(", ")), (if $fixed then "yes" else "no" end) ]
| @tsv'

rows=$(mktemp)
trap 'rm -f "$rows"' EXIT

while [ $# -gt 0 ]; do
  case "$1" in
    --osv | --npm)
      if [ $# -lt 2 ] || [ ! -f "$2" ]; then
        echo "Result file not found: ${2:-}" >&2
        exit 2
      fi
      if [ "$1" = --osv ]; then program=$OSV_ROWS; else program=$NPM_ROWS; fi
      jq -r "$program" "$2" >>"$rows"
      shift 2
      ;;
    *)
      echo "Usage: $0 [--osv file]... [--npm file]..." >&2
      exit 2
      ;;
  esac
done

blocking=$(grep -c '^block' "$rows" || true)

echo "## Dependency vulnerabilities"
echo
if [ "$blocking" -gt 0 ]; then
  echo "${blocking} high or critical vulnerabilities with a fixed version. Upgrade the dependencies"
  echo
  echo "| Ecosystem | Package | Severity | ID | Fix available |"
  echo "|--------|--------|--------|----|--------|"
  grep '^block' "$rows" | sort -u | awk -F'\t' '{ printf "| %s | `%s` | %s | %s | %s |\n", $2, $3, $4, $5, $6 }'
else
  echo "No blocking vulnerabilities (0 fixable high or critical in application dependencies)"
fi
echo
echo "Reported only (not blocking)"
echo
# 생태계별 건수. 없으면 "없음" (grep 대신 awk: 일치가 없어도 실패하지 않음)
awk -F'\t' '$1 == "report" { count[$2]++; total++ } END { if (total == 0) print "- none"; for (e in count) printf "- %s: %d\n", e, count[e] }' "$rows" | sort

[ "$blocking" -eq 0 ]
