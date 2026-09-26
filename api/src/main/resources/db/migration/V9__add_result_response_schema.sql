-- 응답 JSON의 구조 지문 (경로별 관측 타입). 스키마 드리프트 감지의 기준 데이터
-- 원문(헤더·본문)과 달리 보관기간이 지나도 삭제하지 않음. JSON이 아닌 응답·응답 없는 결과·이전 기록은 NULL

ALTER TABLE test_result ADD COLUMN response_schema JSON;
