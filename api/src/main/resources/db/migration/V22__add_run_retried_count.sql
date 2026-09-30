-- 응답을 받지 못해 다시 보낸 결과 수. 판정 수(V10)와 같이 결과를 저장하는 트랜잭션에서 함께 올림
-- 불안정 케이스 조회가 재시도한 실행만 골라 결과를 읽도록 실행 행에 보관 (결과 테이블 전체를 읽지 않음)
-- 재시도 기록(V20) 이후 결과만 채움. 1년치(결과 1천만 건)에서 0.8초

ALTER TABLE test_run ADD COLUMN retried_count INTEGER DEFAULT 0 NOT NULL;

UPDATE test_run
SET retried_count = (SELECT COUNT(*) FROM test_result r WHERE r.run_id = test_run.id AND r.retry_count > 0)
WHERE id IN (SELECT r.run_id FROM test_result r WHERE r.retry_count > 0);
