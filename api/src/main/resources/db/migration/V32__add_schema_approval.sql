-- 구조 변화 승인 (docs/design.md "스키마 드리프트 감지" 3단계 2차)
-- 승인 대기(schema_pending) 결과를 사용자가 기준선에 반영한 기록. 승인한 결과만 값이 있음
-- (기록할 때 바로 받아들인 결과·기존 결과는 null)

ALTER TABLE test_result ADD COLUMN schema_approved_by BIGINT;
ALTER TABLE test_result ADD COLUMN schema_approved_at TIMESTAMP(6) WITH TIME ZONE;
