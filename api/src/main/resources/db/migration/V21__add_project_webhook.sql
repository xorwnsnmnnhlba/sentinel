-- 프로젝트별 실패 알림 채널. 지정하면 그 프로젝트의 스케줄 실행 실패·복구 알림을 이 Webhook으로 보냄 (없으면 서버 기본 채널)
-- 주소는 자격증명이므로 환경 시크릿과 같은 키로 암호화하여 저장. 평문은 보관하지 않고 조회 응답에도 내보내지 않음

ALTER TABLE project ADD COLUMN webhook_url_cipher VARCHAR(4000);
