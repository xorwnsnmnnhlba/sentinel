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

	/**
	 * 같은 스위트·환경의 이전 스케줄 실행 (완료만), 최근 순. 실패 연속 여부·복구 판단용
	 */
	List<TestRun> findBySuiteIdAndEnvironmentIdAndTriggerAndStatusAndIdLessThanOrderByIdDesc(Long suiteId,
			Long environmentId, TestRun.Trigger trigger, TestRun.Status status, Long id, Limit limit);

	/**
	 * 기간 안에 시작한 스위트 전체 실행의 스위트별 합계 (케이스 단건 실행 제외). 실행 행의 판정 수(V10)만 사용.
	 * 기간 조건은 {@code idx_run_time}
	 */
	@Query("""
			select t.suite.id as suiteId, count(t) as runs,
			       sum(case when t.failedCount + t.errorCount > 0 then 1 else 0 end) as failingRuns,
			       sum(t.passedCount) as passed, sum(t.failedCount) as failed, sum(t.errorCount) as errors
			from TestRun t
			where t.startedAt >= :from and t.startedAt < :to
			  and t.testCaseId is null
			group by t.suite.id
			""")
	List<SuiteRunTotals> summarizeBySuite(@Param("from") Instant from, @Param("to") Instant to);

	interface SuiteRunTotals {

		Long getSuiteId();

		long getRuns();

		long getFailingRuns();

		long getPassed();

		long getFailed();

		long getErrors();

	}

	/**
	 * 스위트별 가장 최근 스위트 전체 실행. ID는 시작 순으로 증가하므로 최대 ID가 최근 실행
	 * 케이스 단건 실행은 제외 (한 케이스만 통과해도 스위트가 통과한 것처럼 보이지 않도록)
	 */
	@Query("""
			select t from TestRun t
			where t.id in (select max(latest.id) from TestRun latest where latest.testCaseId is null group by latest.suite.id)
			""")
	List<TestRun> findLatestPerSuite();

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
