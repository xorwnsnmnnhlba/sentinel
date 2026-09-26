-- 케이스 단건 실행의 대상. 스위트 전체 실행과 이전 기록은 NULL
-- 스위트 단위 집계(대시보드 스위트 요약·최근 실행)에서 단건 실행을 제외하는 기준
-- 케이스 삭제(보관) 후에도 이력이 남아야 하므로 ID만 보관하고 이름은 조회 시점에 확인

ALTER TABLE test_run ADD COLUMN test_case_id BIGINT;
