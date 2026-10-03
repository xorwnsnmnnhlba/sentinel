-- 스케줄 실행의 실패 알림 발송 시각. 같은 케이스가 계속 실패하는 동안 재알림 간격(sentinel.notification.repeat-interval) 판단용
-- 새로 실패한 케이스가 있거나, 같은 스위트·환경에서 간격 안에 발송한 실행이 없을 때만 발송하고 기록. null이면 발송하지 않음
-- 조회는 스위트·시작 시각 인덱스(idx_run_suite_time)로 간격 안의 실행만 읽음

ALTER TABLE test_run ADD COLUMN failure_notified_at TIMESTAMP(6) WITH TIME ZONE;
