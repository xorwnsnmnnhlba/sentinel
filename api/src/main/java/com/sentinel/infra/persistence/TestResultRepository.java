package com.sentinel.infra.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import com.sentinel.domain.TestResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 응답시간 백분위({@code PERCENTILE_CONT})·구조 지문 기준선({@code LATERAL})·판정 흐름({@code LAG})은 JPQL로 표현할 수 없어
 * 네이티브 SQL (SQL 표준 문법).
 * 그 외 조건 조회·일괄 변경은 {@link TestResultQueries} (QueryDSL)
 */
public interface TestResultRepository extends JpaRepository<TestResult, Long>, TestResultQueries {

	List<TestResult> findByRunIdOrderById(Long runId);

	/**
	 * 케이스 1건의 일별 응답시간 (응답을 받은 결과만). 일자는 {@code zone} 기준 실행 시작 날짜.
	 * <p>
	 * 네이티브 쿼리는 hibernate.default_schema가 적용되지 않으므로 테이블에 {@code {h-schema}}(기본 스키마)를 붙임.
	 * 같은 시간대 식을 SELECT와 GROUP BY에 각각 바인딩하면 PostgreSQL이 다른 식으로 보므로 하위 쿼리에서 한 번만 계산.
	 * 백분위는 SQL 표준 {@code PERCENTILE_CONT} (연속 보간)
	 * @return [일자, 건수, p50, p95, 최대] 일자 순
	 */
	@Query(nativeQuery = true, value = """
			SELECT d.day, COUNT(*),
			       PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY d.elapsed_ms),
			       PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY d.elapsed_ms),
			       MAX(d.elapsed_ms)
			FROM (
			    SELECT CAST(t.started_at AT TIME ZONE :zone AS DATE) AS day, r.elapsed_ms
			    FROM {h-schema}test_result r
			    JOIN {h-schema}test_run t ON t.id = r.run_id
			    WHERE r.test_case_id = :caseId
			      AND t.environment_id = :environmentId
			      AND r.outcome = 'RESPONDED'
			      AND r.elapsed_ms IS NOT NULL
			      AND t.started_at >= :from
			      AND t.started_at < :to
			) d
			GROUP BY d.day
			ORDER BY d.day
			""")
	List<Object[]> dailyResponseTimes(@Param("caseId") long caseId, @Param("environmentId") long environmentId,
			@Param("zone") String zone, @Param("from") Instant from, @Param("to") Instant to);

	/**
	 * 스위트의 케이스별 응답시간 (응답을 받은 결과만)
	 * @return [케이스 ID, 건수, p50, p95, 최대]
	 */
	@Query(nativeQuery = true, value = """
			SELECT r.test_case_id, COUNT(*),
			       PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY r.elapsed_ms),
			       PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY r.elapsed_ms),
			       MAX(r.elapsed_ms)
			FROM {h-schema}test_result r
			JOIN {h-schema}test_run t ON t.id = r.run_id
			WHERE t.suite_id = :suiteId
			  AND t.environment_id = :environmentId
			  AND r.outcome = 'RESPONDED'
			  AND r.elapsed_ms IS NOT NULL
			  AND t.started_at >= :from
			  AND t.started_at < :to
			GROUP BY r.test_case_id
			""")
	List<Object[]> caseResponseTimes(@Param("suiteId") long suiteId, @Param("environmentId") long environmentId,
			@Param("from") Instant from, @Param("to") Instant to);

	/**
	 * 케이스·환경별 p95를 이전 기간과 현재 기간으로 나누어 집계 (응답을 받은 결과만)
	 * @param from 이전 기간 시작
	 * @param middle 현재 기간 시작 (이전 기간의 미포함 상한)
	 * @param to 현재 기간의 미포함 상한
	 * @return [케이스 ID, 환경 ID, 현재 기간 여부(1·0), 건수, p95]
	 */
	@Query(nativeQuery = true, value = """
			SELECT w.test_case_id, w.environment_id, w.current_window, COUNT(*),
			       PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY w.elapsed_ms)
			FROM (
			    SELECT r.test_case_id, t.environment_id, r.elapsed_ms,
			           CASE WHEN t.started_at >= :middle THEN 1 ELSE 0 END AS current_window
			    FROM {h-schema}test_result r
			    JOIN {h-schema}test_run t ON t.id = r.run_id
			    WHERE r.outcome = 'RESPONDED'
			      AND r.elapsed_ms IS NOT NULL
			      AND t.started_at >= :from
			      AND t.started_at < :to
			) w
			GROUP BY w.test_case_id, w.environment_id, w.current_window
			""")
	List<Object[]> p95ByWindow(@Param("from") Instant from, @Param("middle") Instant middle, @Param("to") Instant to);

	/**
	 * 기간 안 스케줄 실행(완료)의 케이스·환경별 판정 흐름. 불안정 케이스 판단용
	 * <p>
	 * 실패 구간은 통과(또는 기간 첫 실행)에서 실패로 바뀐 횟수. 한 번의 장애는 1, 간헐 실패는 여러 번.
	 * 결과 테이블 전체를 읽지 않도록 실행 행의 판정 수(V10)·재시도 수(V22)로 실패·재시도가 있던 실행만 골라 그 실행의 결과만 읽음
	 * <ul>
	 * <li>실행: 기간 안 스케줄 실행을 스위트·환경별 시작 순으로 놓고 SQL 표준 창 함수 {@code LAG}로 바로 앞 실행을 구함
	 * ({@code idx_run_time})</li>
	 * <li>결과: 실패·재시도가 있던 실행마다 {@code LATERAL}로 {@code idx_result_run}을 읽음. {@code OFFSET 0 ROWS}는 하위 쿼리가
	 * 풀려 결과 테이블 전체를 읽는 계획이 되지 않도록 막는 경계 (창 함수 뒤 조건은 행 수를 추정하지 못해 전체 읽기를 고름)</li>
	 * <li>실패 구간: 실패한 결과 중 바로 앞 실행에서 같은 케이스가 실패하지 않은 것 (앞 실행에 실패가 없으면 결과를 읽지 않음)</li>
	 * </ul>
	 * 1년치(결과 1천만 건, 실행의 34%에 실패가 있는 최악 분포) 30일 362ms (결과 전체에 창 함수 1.2초)
	 * @return [케이스 ID, 환경 ID, 스위트·환경의 스케줄 실행 수, 실패 수, 실패 구간 수, 재시도한 실행 수].
	 *     실패 구간 {@code minEpisodes} 이상 또는 재시도한 실행 {@code minRetried} 이상만
	 */
	@Query(nativeQuery = true, value = """
			SELECT m.test_case_id, m.environment_id, MAX(m.suite_runs), SUM(m.failing), SUM(m.episode), SUM(m.retried)
			FROM (
			    SELECT r.test_case_id, runs.environment_id, runs.suite_runs,
			           CASE WHEN r.verdict IN ('FAILED', 'ERROR') THEN 1 ELSE 0 END AS failing,
			           CASE WHEN r.verdict IN ('FAILED', 'ERROR') AND (runs.previous_failing = 0 OR NOT EXISTS (
			               SELECT 1 FROM {h-schema}test_result p
			               WHERE p.run_id = runs.previous_id AND p.test_case_id = r.test_case_id
			                 AND p.verdict IN ('FAILED', 'ERROR'))) THEN 1 ELSE 0 END AS episode,
			           CASE WHEN r.retry_count > 0 THEN 1 ELSE 0 END AS retried
			    FROM (
			        SELECT t.id, t.environment_id, t.failed_count + t.error_count AS failing, t.retried_count,
			               COUNT(*) OVER (PARTITION BY t.suite_id, t.environment_id) AS suite_runs,
			               LAG(t.id) OVER (PARTITION BY t.suite_id, t.environment_id ORDER BY t.started_at, t.id) AS previous_id,
			               LAG(t.failed_count + t.error_count, 1, 0)
			                   OVER (PARTITION BY t.suite_id, t.environment_id ORDER BY t.started_at, t.id) AS previous_failing
			        FROM {h-schema}test_run t
			        WHERE t.started_at >= :from
			          AND t.started_at < :to
			          AND t.trigger_type = 'SCHEDULED'
			          AND t.status = 'COMPLETED'
			    ) runs
			    CROSS JOIN LATERAL (
			        SELECT r.test_case_id, r.verdict, r.retry_count
			        FROM {h-schema}test_result r
			        WHERE r.run_id = runs.id
			          AND (r.verdict IN ('FAILED', 'ERROR') OR r.retry_count > 0)
			        OFFSET 0 ROWS
			    ) r
			    WHERE runs.failing > 0 OR runs.retried_count > 0
			) m
			GROUP BY m.test_case_id, m.environment_id
			HAVING SUM(m.episode) >= :minEpisodes OR SUM(m.retried) >= :minRetried
			""")
	List<Object[]> caseStability(@Param("from") Instant from, @Param("to") Instant to,
			@Param("minEpisodes") int minEpisodes, @Param("minRetried") int minRetried);

	/**
	 * 케이스별 이전 구조 지문 최근 {@code window}건 (같은 환경, {@code runId}보다 앞선 실행). 구조 변화 판정의 기준선
	 * <p>
	 * 케이스마다 SQL 표준 {@code LATERAL} + {@code FETCH FIRST}로 {@code idx_result_case_time}을 최근 결과부터 읽고
	 * {@code window}건에서 멈춤. 이력 전체를 읽지 않도록 {@code beforeResultId}(이 실행의 첫 결과 ID)보다 앞선 결과부터 읽음.
	 * 결과 ID는 기록 순으로 증가하므로 오래된 실행을 조회해도 그 실행 직전의 결과부터 읽음. JSON 열은 문자열로 변환하여 반환
	 * @param beforeResultId 이 결과 ID보다 작은 결과만 (이 실행의 첫 결과 ID)
	 * @return [케이스 ID, 지문 JSON] 케이스 순, 최근 순
	 */
	@Query(nativeQuery = true, value = """
			SELECT c.id, CAST(s.response_schema AS VARCHAR)
			FROM {h-schema}test_case c
			CROSS JOIN LATERAL (
			    SELECT r.id, r.response_schema
			    FROM {h-schema}test_result r
			    JOIN {h-schema}test_run t ON t.id = r.run_id
			    WHERE r.test_case_id = c.id
			      AND r.id < :beforeResultId
			      AND t.id < :runId
			      AND t.environment_id = :environmentId
			      AND r.response_schema IS NOT NULL
			    ORDER BY r.id DESC
			    FETCH FIRST (:window) ROWS ONLY
			) s
			WHERE c.id IN (:caseIds)
			ORDER BY c.id, s.id DESC
			""")
	List<Object[]> findRecentSchemas(@Param("environmentId") long environmentId,
			@Param("caseIds") Collection<Long> caseIds, @Param("runId") long runId,
			@Param("beforeResultId") long beforeResultId, @Param("window") int window);

}
