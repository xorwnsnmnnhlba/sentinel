-- 외래키 제거. 참조 무결성은 애플리케이션에서 보장
-- 조회용 인덱스(idx_*)와 유니크 제약은 유지

ALTER TABLE environment   DROP CONSTRAINT IF EXISTS fk_environment_project;
ALTER TABLE test_suite    DROP CONSTRAINT IF EXISTS fk_test_suite_project;
ALTER TABLE test_case     DROP CONSTRAINT IF EXISTS fk_test_case_suite;
ALTER TABLE test_run      DROP CONSTRAINT IF EXISTS fk_test_run_suite;
ALTER TABLE test_run      DROP CONSTRAINT IF EXISTS fk_test_run_environment;
ALTER TABLE test_result   DROP CONSTRAINT IF EXISTS fk_test_result_run;
ALTER TABLE test_result   DROP CONSTRAINT IF EXISTS fk_test_result_test_case;
ALTER TABLE test_schedule DROP CONSTRAINT IF EXISTS fk_test_schedule_suite;
ALTER TABLE test_schedule DROP CONSTRAINT IF EXISTS fk_test_schedule_environment;
