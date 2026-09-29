-- 응답을 받지 못해 다시 보낸 횟수. 재시도 뒤 응답을 받았으면 그 응답으로, 끝내 받지 못했으면 마지막 오류로 기록
-- 기존 결과는 재시도가 없었으므로 0. PostgreSQL 11 이상은 상수 기본값 컬럼 추가가 행을 다시 쓰지 않아 큰 테이블에서도 즉시 끝남

ALTER TABLE test_result ADD COLUMN retry_count INTEGER DEFAULT 0 NOT NULL;
