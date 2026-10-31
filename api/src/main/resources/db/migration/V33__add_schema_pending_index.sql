-- 승인 대기 구조 변화 목록 (docs/design.md "스키마 드리프트 감지" 3단계 3차)
-- 대시보드가 케이스·환경별 마지막 승인 대기를 찾을 때 결과 테이블 전체 대신 승인 대기 결과만 읽도록 부분 인덱스.
-- 실행(run_id)을 포함하여 환경별로 나누는 조인을 인덱스만으로. 승인 대기는 전체 결과의 일부라 인덱스가 작음

CREATE INDEX idx_result_schema_pending ON test_result (test_case_id, run_id, id) WHERE schema_pending;
