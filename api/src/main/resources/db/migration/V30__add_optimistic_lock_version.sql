-- 동시 수정 덮어쓰기 방지 (낙관적 잠금, JPA @Version). 같은 행을 두 요청이 동시에 고치면 나중 요청은 409로 실패
-- 스케줄: 사용자 변경과 폴러의 다음 실행 시각 갱신, 환경: 시크릿·변수(JSON 한 칸) 수정, 케이스: 수정과 실행 순서 변경
ALTER TABLE test_schedule ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE environment ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE test_case ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
