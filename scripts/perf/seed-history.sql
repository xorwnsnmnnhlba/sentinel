-- 대용량 실행 이력 생성 (성능 점검용). E2E 스택의 일회용 DB에만 사용 (scripts/perf/README.md)
--
--   docker compose -f docker-compose.e2e.yml exec -T e2e-postgres psql -U sentinel -d sentinel -v ON_ERROR_STOP=1 -v days=365 < scripts/perf/seed-history.sql
--
-- 규모: 프로젝트 4 × 스위트 5 × 케이스 10, 스위트마다 10분 간격 스케줄 days일(기본 45)
--   45일 → 실행 약 13만·결과 약 130만 건(약 0.7GB), 365일 → 실행 약 105만·결과 약 1,050만 건(약 5.3GB)
-- - 최근 30일 결과만 요청·응답 원문 보관, 그 이전은 보관기간 정리를 마친 상태(raw_purged_at)로 생성
-- - 판정 약 96% 통과·3% 실패·1% 오류, 수동 실행 1%(그중 절반은 케이스 단건 실행)
-- - 앱 SQL은 ANSI 표준만 쓰지만(docs/design.md), 이 도구는 대량 생성 속도를 위해 PostgreSQL generate_series·random 사용

SET search_path = sentinel;

\if :{?days}
\else
\set days 45
\endif

\timing on

INSERT INTO project (name, description, created_at, updated_at)
SELECT 'perf-project-' || p, '성능 점검용', now(), now()
FROM generate_series(1, 4) p;

INSERT INTO environment (project_id, name, base_url, variables, created_at, updated_at)
SELECT id, 'dev', 'https://gw.dev.internal', '{}'::json, now(), now()
FROM project WHERE name LIKE 'perf-project-%';

INSERT INTO test_suite (project_id, name, description, created_at, updated_at)
SELECT p.id, 'perf-suite-' || s, NULL, now(), now()
FROM project p CROSS JOIN generate_series(1, 5) s
WHERE p.name LIKE 'perf-project-%';

INSERT INTO test_case (suite_id, name, http_method, path, headers, body, assertions, created_at, updated_at)
SELECT s.id, 'perf-case-' || c, 'GET', '/orders/' || c, '{}'::json, NULL, '[]'::json, now(), now()
FROM test_suite s CROSS JOIN generate_series(1, 10) c
WHERE s.name LIKE 'perf-suite-%';

-- 실행: 스위트마다 10분 간격, days일
-- 앱과 같이 ID가 시작 순으로 증가하도록 시각 순으로 생성 (ID 순 정렬·범위 조건을 쓰는 쿼리의 실행 계획이 실제와 같아짐)
INSERT INTO test_run (suite_id, environment_id, trigger_type, status, started_at, finished_at, triggered_by,
                      raw_purged_at, test_case_id, created_at, updated_at)
SELECT s.id, e.id,
       CASE WHEN random() < 0.01 THEN 'MANUAL' ELSE 'SCHEDULED' END,
       'COMPLETED', t, t + interval '2 seconds', NULL,
       CASE WHEN t < now() - interval '30 days' THEN t + interval '31 days' END,
       NULL, t, t
FROM test_suite s
JOIN environment e ON e.project_id = s.project_id
CROSS JOIN generate_series(now() - make_interval(days => :days), now() - interval '10 minutes', interval '10 minutes') t
WHERE s.name LIKE 'perf-suite-%'
ORDER BY t, s.id;

-- 수동 실행의 절반은 케이스 단건 실행 (스위트의 첫 케이스)
UPDATE test_run r
SET test_case_id = (SELECT min(c.id) FROM test_case c WHERE c.suite_id = r.suite_id)
WHERE r.trigger_type = 'MANUAL' AND r.id % 2 = 0;

-- 결과: 스위트 전체 실행은 케이스 10건, 단건 실행은 대상 케이스 1건
-- 판정용 난수는 행마다 뽑도록 하위 쿼리의 SELECT 목록에서 계산
-- (바깥 열을 참조하지 않는 LATERAL (SELECT random())은 한 번만 계산되어 모든 행의 판정이 같아짐)
INSERT INTO test_result (run_id, test_case_id, outcome, verdict, assertion_results, error_reason, error_message,
                         request_method, request_url, request_headers, request_body, status_code, response_headers,
                         response_body, response_body_truncated, elapsed_ms, response_schema, created_at, updated_at)
SELECT x.run_id, x.case_id,
       CASE WHEN x.roll < 0.01 THEN 'ERROR' ELSE 'RESPONDED' END,
       CASE WHEN x.roll < 0.01 THEN 'ERROR' WHEN x.roll < 0.04 THEN 'FAILED' ELSE 'PASSED' END,
       CASE WHEN x.roll < 0.01 THEN '[]'::json
            WHEN x.roll < 0.04 THEN '[{"description": "status in [2xx]", "passed": false, "message": "was 500"}]'::json
            ELSE '[{"description": "status in [2xx]", "passed": true, "message": "200"}]'::json END,
       CASE WHEN x.roll < 0.01 THEN 'TIMEOUT' END,
       CASE WHEN x.roll < 0.01 THEN 'TIMEOUT: GET https://gw.dev.internal/orders' END,
       'GET', 'https://gw.dev.internal' || x.path,
       CASE WHEN x.raw_purged_at IS NULL THEN '{"Accept": ["application/json"]}'::json END,
       NULL,
       CASE WHEN x.roll < 0.01 THEN NULL WHEN x.roll < 0.04 THEN 500 ELSE 200 END,
       CASE WHEN x.raw_purged_at IS NULL AND x.roll >= 0.01 THEN '{"Content-Type": ["application/json"]}'::json END,
       CASE WHEN x.raw_purged_at IS NULL AND x.roll >= 0.01 THEN '{"id": 1, "name": "order", "items": [{"sku": "a"}]}' END,
       FALSE,
       (20 + x.jitter * 400 + CASE WHEN x.case_name = 'perf-case-1' THEN 300 ELSE 0 END)::bigint,
       CASE WHEN x.roll >= 0.01 THEN '{"fields": {"$": ["object"], "$.id": ["number"], "$.items": ["array"], "$.items[]": ["object"], "$.items[].sku": ["string"], "$.name": ["string"]}, "complete": true}'::json END,
       x.started_at, x.started_at
FROM (
    SELECT r.id AS run_id, r.started_at, r.raw_purged_at, c.id AS case_id, c.name AS case_name, c.path,
           random() AS roll, random() AS jitter
    FROM test_run r
    JOIN test_case c ON c.suite_id = r.suite_id AND (r.test_case_id IS NULL OR c.id = r.test_case_id)
    WHERE r.suite_id IN (SELECT id FROM test_suite WHERE name LIKE 'perf-suite-%')
) x
ORDER BY x.run_id, x.case_id;

-- 실행별 판정 수 (V10 이후 스키마). V10 이전 스키마면 마이그레이션의 채우기가 대신 계산
SELECT EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'sentinel' AND table_name = 'test_run' AND column_name = 'passed_count') AS has_counts \gset
\if :has_counts
UPDATE test_run t
SET passed_count = c.passed, failed_count = c.failed, error_count = c.errors
FROM (SELECT run_id, count(*) FILTER (WHERE verdict = 'PASSED') AS passed,
             count(*) FILTER (WHERE verdict = 'FAILED') AS failed,
             count(*) FILTER (WHERE verdict = 'ERROR') AS errors
      FROM test_result GROUP BY run_id) c
WHERE c.run_id = t.id;
\endif

ANALYZE;

SELECT (SELECT count(*) FROM test_run) AS runs, (SELECT count(*) FROM test_result) AS results,
       pg_size_pretty(pg_database_size('sentinel')) AS db_size;

-- 판정 분포 확인 (약 96% 통과, 3% 실패, 1% 오류)
SELECT verdict, count(*), round(100.0 * count(*) / sum(count(*)) OVER (), 1) AS percent
FROM test_result GROUP BY verdict ORDER BY verdict;
