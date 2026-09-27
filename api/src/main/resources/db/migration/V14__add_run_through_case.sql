-- "여기까지 실행"의 대상 케이스. 스위트의 처음부터 이 케이스까지 실행 순서대로 실행한 기록
-- 케이스 단건 실행(test_case_id)과 같이 스위트 단위 집계(스위트 요약·최근 실행)에서 제외. 스위트 전체 실행·단건 실행은 NULL

ALTER TABLE test_run ADD COLUMN through_case_id BIGINT;
