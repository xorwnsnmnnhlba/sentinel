-- 수동 실행의 주체. 스케줄 실행과 이전 기록은 NULL
-- 사용자 삭제(보관) 후에도 이력이 남아야 하므로 ID만 보관하고 이름은 조회 시점에 확인

ALTER TABLE test_run ADD COLUMN triggered_by BIGINT;
