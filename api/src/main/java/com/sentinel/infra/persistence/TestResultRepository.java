package com.sentinel.infra.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import com.sentinel.domain.TestResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TestResultRepository extends JpaRepository<TestResult, Long> {

	List<TestResult> findByRunIdOrderById(Long runId);

	/** 결과 상세용. 케이스 이름 조회 시 결과 건수만큼 추가 쿼리가 발생하지 않도록 함께 로딩 */
	@Query("select r from TestResult r join fetch r.testCase where r.run.id = :runId order by r.id")
	List<TestResult> findWithTestCaseByRunId(@Param("runId") Long runId);

	/** 실행 목록의 판정별 건수. 목록의 실행 ID를 한 번에 집계 */
	@Query("""
			select r.run.id as runId, r.verdict as verdict, count(r) as count
			from TestResult r
			where r.run.id in :runIds
			group by r.run.id, r.verdict
			""")
	List<VerdictCount> countVerdictsByRunIds(@Param("runIds") Collection<Long> runIds);

	/**
	 * 요청·응답 헤더와 본문을 일괄로 비움. 판정·규칙별 결과·상태 코드·URL·소요 시간은 유지
	 * @return 갱신한 결과 건수
	 */
	@Modifying
	@Query("""
			update TestResult r
			set r.requestHeaders = null, r.requestBody = null, r.responseHeaders = null, r.responseBody = null
			where r.run.id in :runIds
			""")
	int purgeRawByRunIds(@Param("runIds") Collection<Long> runIds);

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

	/** 기간 안에 시작한 실행의 판정별 결과 수 (스위트별) */
	@Query("""
			select t.suite.id as suiteId, r.verdict as verdict, count(r) as count
			from TestResult r
			join r.run t
			where t.startedAt >= :from and t.startedAt < :to
			group by t.suite.id, r.verdict
			""")
	List<SuiteVerdictCount> countVerdictsBySuite(@Param("from") Instant from, @Param("to") Instant to);

	interface SuiteVerdictCount {

		Long getSuiteId();

		TestResult.Verdict getVerdict();

		long getCount();

	}

	/** 실행별 케이스 판정. 연속 실패 계산용 */
	@Query("select r.run.id as runId, r.testCase.id as caseId, r.verdict as verdict from TestResult r where r.run.id in :runIds")
	List<CaseVerdict> findCaseVerdictsByRunIds(@Param("runIds") Collection<Long> runIds);

	interface CaseVerdict {

		Long getRunId();

		Long getCaseId();

		TestResult.Verdict getVerdict();

	}

	interface VerdictCount {

		Long getRunId();

		TestResult.Verdict getVerdict();

		long getCount();

	}

}
