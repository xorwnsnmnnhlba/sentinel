-- 소프트 삭제(보관). 실행 이력이 참조하므로 행 삭제 대신 archived_at 기록
-- archive_key: 사용 중이면 0, 보관 시 자기 ID. 유니크 제약에 포함하여 사용 중인 행끼리의 이름 중복만 차단하고 보관된 이름은 재사용 허용

ALTER TABLE project ADD COLUMN archived_at TIMESTAMP(6) WITH TIME ZONE;
ALTER TABLE project ADD COLUMN archive_key BIGINT DEFAULT 0 NOT NULL;
ALTER TABLE project DROP CONSTRAINT uk_project_name;
ALTER TABLE project ADD CONSTRAINT uk_project_name UNIQUE (name, archive_key);

ALTER TABLE environment ADD COLUMN archived_at TIMESTAMP(6) WITH TIME ZONE;
ALTER TABLE environment ADD COLUMN archive_key BIGINT DEFAULT 0 NOT NULL;
ALTER TABLE environment DROP CONSTRAINT uk_environment_project_name;
ALTER TABLE environment ADD CONSTRAINT uk_environment_project_name UNIQUE (project_id, name, archive_key);

ALTER TABLE test_suite ADD COLUMN archived_at TIMESTAMP(6) WITH TIME ZONE;
ALTER TABLE test_suite ADD COLUMN archive_key BIGINT DEFAULT 0 NOT NULL;
ALTER TABLE test_suite DROP CONSTRAINT uk_test_suite_project_name;
ALTER TABLE test_suite ADD CONSTRAINT uk_test_suite_project_name UNIQUE (project_id, name, archive_key);

ALTER TABLE test_case ADD COLUMN archived_at TIMESTAMP(6) WITH TIME ZONE;
ALTER TABLE test_case ADD COLUMN archive_key BIGINT DEFAULT 0 NOT NULL;
ALTER TABLE test_case DROP CONSTRAINT uk_test_case_suite_name;
ALTER TABLE test_case ADD CONSTRAINT uk_test_case_suite_name UNIQUE (suite_id, name, archive_key);

ALTER TABLE test_schedule ADD COLUMN archived_at TIMESTAMP(6) WITH TIME ZONE;
