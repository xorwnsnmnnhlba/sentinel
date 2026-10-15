#!/usr/bin/env bash
# 의존성 취약점 판정. OSV-Scanner 이미지 점검 결과와 npm audit 결과(JSON)를 읽어 Markdown 요약을 출력하고,
# 막아야 할 취약점이 있으면 종료 코드 1 (.github/workflows/security.yml)
#
#   scripts/vulnerabilities.sh [--osv osv-scanner.json [--expect ecosystem | --expect-package name]...]... [--npm npm-audit.json]...
#
# --expect          바로 앞 --osv 결과에 그 생태계(Maven, Go, Alpine 등. "Alpine"은 "Alpine:v3.22"도 포함) 패키지가 없으면 종료 코드 2
#                   점검기가 jar 등을 놓치면 취약점 0건으로 통과하므로 확인
# --expect-package  바로 앞 --osv 결과에 그 이름(예: org.springframework:spring-core)의 패키지가 없으면 종료 코드 2
#                   점검기가 좌표를 잘못 추정하면 패키지는 있어도 취약점 조회가 빗나가므로 정확한 이름으로 확인
# 둘 다 OSV-Scanner를 --all-packages로 실행해야 함 (기본 출력에는 취약점 있는 패키지만 있음)
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

# 결과 파일의 생태계별 패키지 수. "- osv-api.json: Maven 100, Ubuntu:26.04 106"
OSV_SCANNED='
[.results[]?.packages[]?.package.ecosystem] | group_by(.) | map("\(.[0]) \(length)")
| "- \($file): " + (if length == 0 then "none" else join(", ") end)'

rows=$(mktemp)
scanned=$(mktemp)
trap 'rm -f "$rows" "$scanned"' EXIT
last_osv=""

while [ $# -gt 0 ]; do
  case "$1" in
    --osv | --npm)
      if [ $# -lt 2 ] || [ ! -f "$2" ]; then
        echo "Result file not found: ${2:-}" >&2
        exit 2
      fi
      if [ "$1" = --osv ]; then
        jq -r "$OSV_ROWS" "$2" >>"$rows"
        jq -r --arg file "$(basename "$2")" "$OSV_SCANNED" "$2" >>"$scanned"
        last_osv=$2
      else
        jq -r "$NPM_ROWS" "$2" >>"$rows"
      fi
      shift 2
      ;;
    --expect)
      if [ $# -lt 2 ] || [ -z "$last_osv" ]; then
        echo "--expect needs an ecosystem and must follow --osv" >&2
        exit 2
      fi
      count=$(jq --arg e "$2" '[.results[]?.packages[]?.package.ecosystem | select(. == $e or startswith($e + ":"))] | length' "$last_osv")
      if [ "$count" -eq 0 ]; then
        echo "No $2 packages in $(basename "$last_osv"). The scan missed them or ran without --all-packages; not judging" >&2
        exit 2
      fi
      shift 2
      ;;
    --expect-package)
      if [ $# -lt 2 ] || [ -z "$last_osv" ]; then
        echo "--expect-package needs a package name and must follow --osv" >&2
        exit 2
      fi
      count=$(jq --arg n "$2" '[.results[]?.packages[]?.package.name | select(. == $n)] | length' "$last_osv")
      if [ "$count" -eq 0 ]; then
        echo "No $2 in $(basename "$last_osv"). The scan missed it, misread its name or ran without --all-packages; not judging" >&2
        exit 2
      fi
      shift 2
      ;;
    *)
      echo "Usage: $0 [--osv file [--expect ecosystem | --expect-package name]...]... [--npm file]..." >&2
      exit 2
      ;;
  esac
done

# 같은 패키지를 이미지·Gradle 목록 두 곳에서 찾을 수 있어 중복 행 제외 후 계산
blocking=$(grep '^block' "$rows" | sort -u | grep -c . || true)

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
if [ -s "$scanned" ]; then
  echo
  echo "Scanned packages (OSV-Scanner)"
  echo
  cat "$scanned"
fi

[ "$blocking" -eq 0 ]
