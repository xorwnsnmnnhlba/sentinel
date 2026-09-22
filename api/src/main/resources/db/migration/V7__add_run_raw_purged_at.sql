-- 요청·응답 원문(헤더·본문)을 보관기간 경과 후 비운 시각. NULL이면 원문 보관 중
-- 실행·판정·규칙별 결과는 계속 보관하고 용량이 큰 원문만 정리

ALTER TABLE test_run ADD COLUMN raw_purged_at TIMESTAMP(6) WITH TIME ZONE;

-- 정리 대상 조회용. 원문이 남아 있는(NULL) 실행을 시작 시각 순으로 탐색
CREATE INDEX idx_run_raw_purge ON test_run (raw_purged_at, started_at);
