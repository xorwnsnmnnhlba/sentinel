-- 스위트 안 케이스의 실행 순서 (작을수록 먼저, 같으면 ID 순). 화면 표시 순서도 같음
-- 표준 예약어 POSITION 대신 run_order 사용
-- 기존 케이스는 지금까지의 실행 순서(ID 순)를 스위트별 1부터 채움

ALTER TABLE test_case ADD COLUMN run_order INTEGER DEFAULT 0 NOT NULL;

UPDATE test_case SET run_order = (
    SELECT COUNT(*) FROM test_case c WHERE c.suite_id = test_case.suite_id AND c.id <= test_case.id);
