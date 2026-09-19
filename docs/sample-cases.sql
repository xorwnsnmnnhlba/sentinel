-- Sentinel 샘플 케이스 등록 SQL
--
-- 실행: docker exec -i postgres psql -U postgres -d sentinel -v ON_ERROR_STOP=1 < docs/sample-cases.sql
--
-- 작성 원칙
-- - 표준 SQL만 사용. RETURNING, :: 캐스트, now() 등 PostgreSQL 전용 문법 미사용
-- - ID 하드코딩 없이 유니크한 이름(프로젝트명, 프로젝트 내 스위트명)으로 부모 ID 조회
-- - 부모 이름 오타 시 서브쿼리가 NULL을 반환하여 NOT NULL 제약 위반으로 실패. 외래키 없이도 잘못된 참조 차단
-- - 한 트랜잭션으로 등록. 같은 이름의 프로젝트가 이미 있으면 유니크 제약으로 실패 (ON_ERROR_STOP으로 전체 중단)


-- =====================================================================================
-- 1. 자체 점검 스위트: Sentinel API 자신을 호출. 외부 GW 없이 바로 실행 가능
--    컨테이너 안에서 실행되므로 base_url의 localhost는 sentinel-api 컨테이너 자신
--    4건 중 2건은 의도적 실패(판정 실패 1, 변수 누락 오류 1). 실패 알림 형식 확인용
-- =====================================================================================

START TRANSACTION;

INSERT INTO sentinel.project (name, description, created_at, updated_at)
VALUES ('sentinel-self-check', 'Sentinel API 자체 점검 샘플', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO sentinel.environment (project_id, name, base_url, variables, created_at, updated_at)
VALUES ((SELECT id FROM sentinel.project WHERE name = 'sentinel-self-check'),
        'local', 'http://localhost:8080', '{"missingRunId": "999999", "apiPrefix": "/api"}',
        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO sentinel.test_suite (project_id, name, description, created_at, updated_at)
VALUES ((SELECT id FROM sentinel.project WHERE name = 'sentinel-self-check'),
        'smoke', '판정 규칙 5종·변수·헤더 사용 예시', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- 통과: 판정 규칙 5종 전부 사용
INSERT INTO sentinel.test_case (suite_id, name, http_method, path, headers, body, assertions, created_at, updated_at)
VALUES ((SELECT s.id FROM sentinel.test_suite s JOIN sentinel.project p ON p.id = s.project_id
         WHERE p.name = 'sentinel-self-check' AND s.name = 'smoke'),
        '존재하지 않는 실행 조회는 404 ProblemDetail', 'GET', '{{apiPrefix}}/runs/{{missingRunId}}',
        '{"Accept": ["application/json"]}',
        NULL,
        '[{"type": "statusCode", "anyOf": ["404"]},
          {"type": "responseTime", "maxMillis": 2000},
          {"type": "header", "name": "Content-Type", "operator": "CONTAINS", "value": "problem+json"},
          {"type": "jsonPath", "path": "$.status", "operator": "EQUALS", "expected": 404},
          {"type": "jsonPath", "path": "$.title", "operator": "EQUALS", "expected": "Not Found"},
          {"type": "bodyContains", "text": "not found"}]',
        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- 통과: POST + 요청 본문 + 4xx 판정. 검증 단계에서 거부되므로 데이터 생성 없음
INSERT INTO sentinel.test_case (suite_id, name, http_method, path, headers, body, assertions, created_at, updated_at)
VALUES ((SELECT s.id FROM sentinel.test_suite s JOIN sentinel.project p ON p.id = s.project_id
         WHERE p.name = 'sentinel-self-check' AND s.name = 'smoke'),
        '스케줄 등록 필수값 누락은 400', 'POST', '{{apiPrefix}}/schedules',
        '{"Content-Type": ["application/json"]}',
        '{}',
        '[{"type": "statusCode", "anyOf": ["4xx"]},
          {"type": "jsonPath", "path": "$.status", "operator": "EQUALS", "expected": 400}]',
        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- 의도적 실패(FAILED): 404 응답에 200 기대
INSERT INTO sentinel.test_case (suite_id, name, http_method, path, headers, body, assertions, created_at, updated_at)
VALUES ((SELECT s.id FROM sentinel.test_suite s JOIN sentinel.project p ON p.id = s.project_id
         WHERE p.name = 'sentinel-self-check' AND s.name = 'smoke'),
        '[의도적 실패] 판정 실패 알림 확인', 'GET', '{{apiPrefix}}/runs/{{missingRunId}}',
        '{}',
        NULL,
        '[{"type": "statusCode", "anyOf": ["200"]}]',
        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- 의도적 실패(ERROR): 환경에 없는 변수 참조. 전송하지 않고 UNRESOLVED_VARIABLE 기록
INSERT INTO sentinel.test_case (suite_id, name, http_method, path, headers, body, assertions, created_at, updated_at)
VALUES ((SELECT s.id FROM sentinel.test_suite s JOIN sentinel.project p ON p.id = s.project_id
         WHERE p.name = 'sentinel-self-check' AND s.name = 'smoke'),
        '[의도적 실패] 변수 누락 오류 확인', 'GET', '{{apiPrefix}}/runs/{{undefinedRunId}}',
        '{}',
        NULL,
        NULL,
        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

COMMIT;


-- =====================================================================================
-- 2. 등록 후 실행·스케줄 (셸에서 실행)
-- =====================================================================================
--
-- 등록된 ID 확인:
--   SELECT s.id AS suite_id, e.id AS environment_id
--   FROM sentinel.test_suite s
--   JOIN sentinel.project p ON p.id = s.project_id
--   JOIN sentinel.environment e ON e.project_id = p.id
--   WHERE p.name = 'sentinel-self-check';
--
-- 스위트 즉시 실행 (<suiteId>, <environmentId> 치환):
--   curl -s -X POST localhost:8080/api/runs \
--     -H 'Content-Type: application/json' -d '{"suiteId": <suiteId>, "environmentId": <environmentId>}'
--   → summary: total 4, passed 2, failed 1, errors 1
--
-- 스케줄 등록 (평일 09:10). next_run_at 계산이 필요하므로 SQL 대신 API 사용:
--   curl -s -X POST localhost:8080/api/schedules -H 'Content-Type: application/json' \
--     -d '{"suiteId": <suiteId>, "environmentId": <environmentId>, "cron": "10 9 * * 1-5"}'
--
-- 실행 완료·실패 알림 확인 (알림은 채널 확정 전까지 로그):
--   docker compose logs sentinel-api | grep "completed:"
--   docker compose logs sentinel-api | grep -A5 Notification


-- =====================================================================================
-- 3. GW 케이스 템플릿: <...> 부분을 실제 값으로 바꾼 뒤 주석 해제하여 사용
--    주의: 환경 변수(variables)는 아직 평문 저장. 운영 토큰·API 키 입력 금지 (암호화 구현 후 시크릿으로 분리 예정)
-- =====================================================================================
--
-- START TRANSACTION;
--
-- INSERT INTO sentinel.project (name, description, created_at, updated_at)
-- VALUES ('<프로젝트명>', '<설명>', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
--
-- INSERT INTO sentinel.environment (project_id, name, base_url, variables, created_at, updated_at)
-- VALUES ((SELECT id FROM sentinel.project WHERE name = '<프로젝트명>'),
--         'dev', 'https://<gw-host>', '{"userId": "<테스트 사용자 ID>"}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
--
-- INSERT INTO sentinel.test_suite (project_id, name, description, created_at, updated_at)
-- VALUES ((SELECT id FROM sentinel.project WHERE name = '<프로젝트명>'),
--         '<스위트명, 예: billing>', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
--
-- INSERT INTO sentinel.test_case (suite_id, name, http_method, path, headers, body, assertions, created_at, updated_at)
-- VALUES ((SELECT s.id FROM sentinel.test_suite s JOIN sentinel.project p ON p.id = s.project_id
--          WHERE p.name = '<프로젝트명>' AND s.name = '<스위트명, 예: billing>'),
--         '결제 내역 조회', 'GET', '/billing/v1/history?userId={{userId}}',
--         '{"Accept": ["application/json"]}',
--         NULL,
--         '[{"type": "statusCode", "anyOf": ["200"]},
--           {"type": "responseTime", "maxMillis": 2000},
--           {"type": "jsonPath", "path": "$.items", "operator": "EXISTS"}]',
--         CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
--
-- INSERT INTO sentinel.test_case (suite_id, name, http_method, path, headers, body, assertions, created_at, updated_at)
-- VALUES ((SELECT s.id FROM sentinel.test_suite s JOIN sentinel.project p ON p.id = s.project_id
--          WHERE p.name = '<프로젝트명>' AND s.name = '<스위트명, 예: billing>'),
--         '결제 요청', 'POST', '/billing/v1/charge',
--         '{"Content-Type": ["application/json"]}',
--         '{"userId": "{{userId}}", "amount": 1000}',
--         '[{"type": "statusCode", "anyOf": ["2xx"]},
--           {"type": "jsonPath", "path": "$.amount", "operator": "EQUALS", "expected": 1000}]',
--         CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
--
-- COMMIT;
--
-- 판정 규칙 전체 목록: api/README.md "판정 규칙 (assertions)"
