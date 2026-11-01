-- 구조 변화 판정 점검 (읽기 전용). 승인 대기가 실행 알림으로 쓸 만큼 정확한지 실제 이력으로 확인 (docs/deploy.md "구조 변화 판정 점검")
--
--   psql "host=<RDS 엔드포인트> dbname=<DB> user=<계정> sslmode=require" -v days=14 < scripts/drift-report.sql
--
-- 대기 구간: 케이스·환경별로 구조 지문이 있는 결과를 순서대로 놓았을 때 승인 대기가 이어진 구간
-- - passed(지나감): 승인 없이 뒤에 받아들인 결과가 옴 (일시적인 오류 응답 등. 알림을 보냈다면 오탐 후보)
-- - approved(승인): 구간 안의 결과를 기준선에 반영 (의도한 API 변경)
-- - open(남음): 마지막 결과까지 대기 (대시보드 "승인 대기 구조 변화")
-- consecutive k: 대기가 k회 이어졌을 때 알린다면 보냈을 알림 수와 그 구간의 결말 (1 = 대기마다 바로 알림)
-- 등급(호환 깨짐·주의)은 저장하지 않고 조회 때 계산하므로 여기서는 나누지 않음. 기간 시작 전부터 이어진 구간은 기간 안 부분만 셈
-- 보관된(삭제한) 케이스·환경 제외. 앱 SQL은 ANSI 표준만 쓰지만(docs/design.md), 이 도구는 PostgreSQL 창 함수·FILTER 사용

\set ON_ERROR_STOP on
\set QUIET on

\if :{?days}
\else
\set days 14
\endif

BEGIN READ ONLY;
SET LOCAL search_path = sentinel;
-- 운영 DB에서 오래 붙잡지 않도록
SET LOCAL statement_timeout = '60s';

-- 읽기 전용 트랜잭션은 임시 뷰도 만들 수 없어 조회마다 같은 WITH를 씀
-- 대기 구간 번호: 전체 순번 - 대기 여부별 순번이 같으면 같은 구간

\echo
\echo '== Overview (last' :days 'days) =='
WITH fingerprinted AS (
    SELECT r.id, r.test_case_id, t.environment_id, r.schema_pending AS pending,
           r.schema_approved_at IS NOT NULL AS approved, t.started_at
    FROM test_result r
    JOIN test_run t ON t.id = r.run_id
    JOIN test_case c ON c.id = r.test_case_id
    JOIN environment e ON e.id = t.environment_id
    WHERE r.response_schema IS NOT NULL
      AND t.started_at >= now() - make_interval(days => :days)
      AND c.archived_at IS NULL
      AND e.archived_at IS NULL
)
SELECT count(*) AS fingerprinted_results,
       count(*) FILTER (WHERE pending) AS pending_results,
       round(100.0 * count(*) FILTER (WHERE pending) / nullif(count(*), 0), 2) AS pending_percent,
       count(*) FILTER (WHERE approved) AS approved_results,
       count(DISTINCT (test_case_id, environment_id)) AS case_environments
FROM fingerprinted;

\echo
\echo '== Pending episodes reaching k consecutive results (alerts if sent at k; passed = reverted without approval, noise candidate) =='
WITH fingerprinted AS (
    SELECT r.id, r.test_case_id, t.environment_id, r.schema_pending AS pending,
           r.schema_approved_at IS NOT NULL AS approved, t.started_at
    FROM test_result r
    JOIN test_run t ON t.id = r.run_id
    JOIN test_case c ON c.id = r.test_case_id
    JOIN environment e ON e.id = t.environment_id
    WHERE r.response_schema IS NOT NULL
      AND t.started_at >= now() - make_interval(days => :days)
      AND c.archived_at IS NULL
      AND e.archived_at IS NULL
),
episodes AS (
    SELECT x.test_case_id, x.environment_id, count(*) AS results, max(x.started_at) AS ended_at,
           CASE WHEN bool_or(x.approved) THEN 'approved'
                WHEN max(x.id) > coalesce(max(x.last_accepted_id), 0) THEN 'open'
                ELSE 'passed' END AS state
    FROM (
        SELECT f.*,
               row_number() OVER (PARTITION BY f.test_case_id, f.environment_id ORDER BY f.id)
                 - row_number() OVER (PARTITION BY f.test_case_id, f.environment_id, f.pending ORDER BY f.id) AS grp,
               max(f.id) FILTER (WHERE NOT f.pending) OVER (PARTITION BY f.test_case_id, f.environment_id) AS last_accepted_id
        FROM fingerprinted f
    ) x
    WHERE x.pending
    GROUP BY x.test_case_id, x.environment_id, x.grp
)
SELECT k.k AS consecutive,
       count(e.state) FILTER (WHERE e.results >= k.k) AS alerts,
       count(e.state) FILTER (WHERE e.results >= k.k AND e.state = 'passed') AS passed,
       count(e.state) FILTER (WHERE e.results >= k.k AND e.state = 'approved') AS approved,
       count(e.state) FILTER (WHERE e.results >= k.k AND e.state = 'open') AS open
FROM (VALUES (1), (2), (3), (5), (10)) k(k)
LEFT JOIN episodes e ON TRUE
GROUP BY k.k
ORDER BY k.k;

\echo
\echo '== Case environments with the most pending episodes (top 20) =='
WITH fingerprinted AS (
    SELECT r.id, r.test_case_id, t.environment_id, r.schema_pending AS pending,
           r.schema_approved_at IS NOT NULL AS approved, t.started_at
    FROM test_result r
    JOIN test_run t ON t.id = r.run_id
    JOIN test_case c ON c.id = r.test_case_id
    JOIN environment e ON e.id = t.environment_id
    WHERE r.response_schema IS NOT NULL
      AND t.started_at >= now() - make_interval(days => :days)
      AND c.archived_at IS NULL
      AND e.archived_at IS NULL
),
episodes AS (
    SELECT x.test_case_id, x.environment_id, count(*) AS results, max(x.started_at) AS ended_at,
           CASE WHEN bool_or(x.approved) THEN 'approved'
                WHEN max(x.id) > coalesce(max(x.last_accepted_id), 0) THEN 'open'
                ELSE 'passed' END AS state
    FROM (
        SELECT f.*,
               row_number() OVER (PARTITION BY f.test_case_id, f.environment_id ORDER BY f.id)
                 - row_number() OVER (PARTITION BY f.test_case_id, f.environment_id, f.pending ORDER BY f.id) AS grp,
               max(f.id) FILTER (WHERE NOT f.pending) OVER (PARTITION BY f.test_case_id, f.environment_id) AS last_accepted_id
        FROM fingerprinted f
    ) x
    WHERE x.pending
    GROUP BY x.test_case_id, x.environment_id, x.grp
)
SELECT p.name AS project, s.name AS suite, c.name AS test_case, env.name AS environment,
       count(*) AS episodes,
       count(*) FILTER (WHERE e.state = 'passed') AS passed,
       count(*) FILTER (WHERE e.state = 'approved') AS approved,
       count(*) FILTER (WHERE e.state = 'open') AS open,
       max(e.results) AS longest,
       max(e.ended_at) AS last_pending_at
FROM episodes e
JOIN test_case c ON c.id = e.test_case_id
JOIN test_suite s ON s.id = c.suite_id
JOIN project p ON p.id = s.project_id
JOIN environment env ON env.id = e.environment_id
GROUP BY p.name, s.name, c.name, env.name
ORDER BY episodes DESC, longest DESC, project, suite, test_case, environment
FETCH FIRST 20 ROWS ONLY;

ROLLBACK;
