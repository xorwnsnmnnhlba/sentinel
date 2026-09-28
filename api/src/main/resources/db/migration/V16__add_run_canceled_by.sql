-- 실행을 중지한 사용자. 중지된 실행(status CANCELED)만 값이 있고 중지 시각은 finished_at
-- status는 VARCHAR라 새 값(CANCELED) 추가에 스키마 변경 불필요

ALTER TABLE test_run ADD COLUMN canceled_by BIGINT;
