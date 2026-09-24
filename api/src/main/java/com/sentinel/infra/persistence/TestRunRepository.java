package com.sentinel.infra.persistence;

import java.time.Instant;
import java.util.List;

import com.sentinel.domain.TestRun;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TestRunRepository extends JpaRepository<TestRun, Long>, JpaSpecificationExecutor<TestRun> {

	List<TestRun> findByStatus(TestRun.Status status);

	/** 기간 안에 시작한 실행 수 (스위트별) */
	@Query("""
			select t.suite.id as suiteId, count(t) as count
			from TestRun t
			where t.startedAt >= :from and t.startedAt < :to
			group by t.suite.id
			""")
	List<SuiteCount> countBySuite(@Param("from") Instant from, @Param("to") Instant to);

	/** 기간 안에 시작한 실행 중 판정 실패·오류 결과가 있는 실행 수 (스위트별) */
	@Query("""
			select t.suite.id as suiteId, count(t) as count
			from TestRun t
			where t.startedAt >= :from and t.startedAt < :to
			  and exists (
			      select r.id from TestResult r
			      where r.run = t
			        and r.verdict in (com.sentinel.domain.TestResult.Verdict.FAILED, com.sentinel.domain.TestResult.Verdict.ERROR))
			group by t.suite.id
			""")
	List<SuiteCount> countFailingBySuite(@Param("from") Instant from, @Param("to") Instant to);

	/** 스위트별 가장 최근 실행. ID는 시작 순으로 증가하므로 최대 ID가 최근 실행 */
	@Query("select t from TestRun t where t.id in (select max(latest.id) from TestRun latest group by latest.suite.id)")
	List<TestRun> findLatestPerSuite();

	interface SuiteCount {

		Long getSuiteId();

		long getCount();

	}

	/**
	 * 원문 정리 대상. 원문이 남아 있고 {@code cutoff} 이전에 시작한 종료된 실행을 오래된 순으로 조회 ({@code idx_run_raw_purge})
	 */
	List<TestRun> findByRawPurgedAtIsNullAndStartedAtBeforeAndStatusNotOrderByStartedAt(Instant cutoff,
			TestRun.Status excluded, Limit limit);

	/**
	 * 실행 목록. 스위트·프로젝트·환경 이름을 함께 로딩하여 행마다 추가 쿼리 방지.
	 * 조건은 {@code RunFilter}가 Specification으로 조립 (null 조건은 SQL에 포함되지 않음)
	 */
	@Override
	@EntityGraph(attributePaths = { "suite", "suite.project", "environment" })
	Page<TestRun> findAll(Specification<TestRun> spec, Pageable pageable);

}
