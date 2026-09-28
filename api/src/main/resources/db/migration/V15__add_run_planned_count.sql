-- 실행할 케이스 수. 실행 시작 시 기록하여 진행 중 화면에 "N건 중 M건 완료" 표시 (수동 실행은 백그라운드 진행)
-- 이전 실행은 NULL

ALTER TABLE test_run ADD COLUMN planned_count INTEGER;
