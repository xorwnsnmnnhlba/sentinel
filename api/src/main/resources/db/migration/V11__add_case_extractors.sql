-- 응답 값 추출 규칙 (이름, 위치 BODY·HEADER, JSON 경로·헤더 이름, 가림 여부). 판정 규칙과 같이 JSON으로 저장
-- 이전 케이스는 NULL (추출 없음)

ALTER TABLE test_case ADD COLUMN extractors JSON;
