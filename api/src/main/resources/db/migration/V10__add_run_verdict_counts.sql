-- 실행별 판정 수. 결과를 저장할 때마다 같은 트랜잭션에서 올림 (실행 중·중단된 실행도 저장된 결과 기준으로 정확)
-- 실행 이력의 실패·오류 조건과 대시보드 스위트 집계가 결과 테이블(실행 수 × 케이스 수)을 훑지 않고 실행 행만 읽도록 함
-- 근거: 1년치(실행 약 105만·결과 약 1,050만 건) 측정 (docs/design.md "대용량 이력 성능 점검")

ALTER TABLE test_run ADD COLUMN passed_count INTEGER DEFAULT 0 NOT NULL;
ALTER TABLE test_run ADD COLUMN failed_count INTEGER DEFAULT 0 NOT NULL;
ALTER TABLE test_run ADD COLUMN error_count INTEGER DEFAULT 0 NOT NULL;

-- 기존 실행 채우기. 판정이 없는 이전 기록(verdict NULL)은 세지 않음 (기존 집계와 같음)
UPDATE test_run SET
    passed_count = (SELECT COUNT(*) FROM test_result r WHERE r.run_id = test_run.id AND r.verdict = 'PASSED'),
    failed_count = (SELECT COUNT(*) FROM test_result r WHERE r.run_id = test_run.id AND r.verdict = 'FAILED'),
    error_count = (SELECT COUNT(*) FROM test_result r WHERE r.run_id = test_run.id AND r.verdict = 'ERROR');

-- 스위트와 무관한 기간 조회·최신순 목록 (실행 이력 첫 페이지, 대시보드 기간 집계)
CREATE INDEX idx_run_time ON test_run (started_at DESC, id DESC);
