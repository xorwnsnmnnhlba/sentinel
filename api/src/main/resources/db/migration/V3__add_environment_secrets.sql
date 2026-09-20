-- 환경별 시크릿(토큰·API 키). 값은 애플리케이션에서 암호화한 문자열로 저장
-- pgcrypto 미사용: 표준 SQL 유지와 DB 접근만으로 복호화 불가하도록 키를 애플리케이션이 보관
-- 이름 목록은 화면·API에 노출하되 값은 조회 불가

ALTER TABLE environment ADD COLUMN secrets JSON;
