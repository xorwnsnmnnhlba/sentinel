package com.sentinel.infra.persistence;

import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import com.sentinel.TestcontainersConfiguration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V10 마이그레이션의 기존 실행 판정 수 채우기. 별도 스키마에 V9까지 적용하고 이력을 넣은 뒤 V10 적용
 * <p>
 * Flyway는 자체 연결을 쓰므로 넣은 이력이 보이도록 테스트 트랜잭션 없이 실행
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class VerdictCountBackfillTest {

	private static final String SCHEMA = "backfill_check";

	@Autowired
	private DataSource dataSource;

	@AfterEach
	void dropSchema() {
		new JdbcTemplate(dataSource).execute("DROP SCHEMA " + SCHEMA + " CASCADE");
	}

	@Test
	void fillsTheVerdictCountsOfExistingRuns() {
		migrateTo("9");
		JdbcTemplate jdbc = new JdbcTemplate(dataSource);
		long mixed = insertRun(jdbc, 1);
		long empty = insertRun(jdbc, 2);
		long other = insertRun(jdbc, 3);
		for (String verdict : new String[] { "PASSED", "PASSED", "FAILED", "ERROR", null }) {
			insertResult(jdbc, mixed, verdict);
		}
		insertResult(jdbc, other, "FAILED");

		migrateTo("10");

		// 판정이 없는 이전 기록(verdict NULL)은 세지 않음. 결과가 없는 실행은 0
		assertThat(counts(jdbc)).containsExactly(
				Map.<String, Object>of("id", mixed, "passed", 2, "failed", 1, "errors", 1),
				Map.<String, Object>of("id", empty, "passed", 0, "failed", 0, "errors", 0),
				Map.<String, Object>of("id", other, "passed", 0, "failed", 1, "errors", 0));
	}

	private void migrateTo(String version) {
		Flyway.configure()
			.dataSource(dataSource)
			.schemas(SCHEMA)
			.defaultSchema(SCHEMA)
			.target(version)
			.load()
			.migrate();
	}

	private static long insertRun(JdbcTemplate jdbc, long id) {
		jdbc.update("""
				INSERT INTO %s.test_run (id, suite_id, environment_id, trigger_type, status, started_at, created_at,
				                         updated_at)
				VALUES (?, 1, 1, 'SCHEDULED', 'COMPLETED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""".formatted(SCHEMA), id);
		return id;
	}

	private static void insertResult(JdbcTemplate jdbc, long runId, String verdict) {
		jdbc.update("""
				INSERT INTO %s.test_result (run_id, test_case_id, outcome, verdict, request_method,
				                            response_body_truncated, created_at, updated_at)
				VALUES (?, 1, 'RESPONDED', ?, 'GET', FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""".formatted(SCHEMA), runId, verdict);
	}

	private static List<Map<String, Object>> counts(JdbcTemplate jdbc) {
		return jdbc.queryForList("""
				SELECT id, passed_count AS passed, failed_count AS failed, error_count AS errors
				FROM %s.test_run ORDER BY id
				""".formatted(SCHEMA));
	}

}
