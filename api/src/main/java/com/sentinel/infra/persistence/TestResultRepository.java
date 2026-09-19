package com.sentinel.infra.persistence;

import java.util.Collection;
import java.util.List;

import com.sentinel.domain.TestResult;
import org.springframework.data.jpa.repository.JpaRepository;
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

	interface VerdictCount {

		Long getRunId();

		TestResult.Verdict getVerdict();

		long getCount();

	}

}
