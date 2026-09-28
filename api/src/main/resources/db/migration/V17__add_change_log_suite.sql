-- 변경 이력의 소속 스위트. 스위트 이력 화면에서 스위트와 그 안의 케이스·스케줄 변경을 함께 조회
-- 스위트는 자신, 케이스·스케줄은 소속 스위트, 그 외(프로젝트·환경·사용자·초대)는 NULL
-- 기존 기록은 대상의 현재 소속으로 채움 (케이스·스케줄은 스위트를 옮길 수 없어 기록 당시와 같음)

ALTER TABLE change_log ADD COLUMN suite_id BIGINT;

UPDATE change_log SET suite_id = target_id WHERE target_type = 'SUITE';
UPDATE change_log SET suite_id = (SELECT c.suite_id FROM test_case c WHERE c.id = change_log.target_id)
WHERE target_type = 'CASE';
UPDATE change_log SET suite_id = (SELECT s.suite_id FROM test_schedule s WHERE s.id = change_log.target_id)
WHERE target_type = 'SCHEDULE';

CREATE INDEX idx_change_log_suite ON change_log (suite_id, id DESC);
