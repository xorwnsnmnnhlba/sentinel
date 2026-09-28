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
import org.springframework.data.jpa.repository.Modifying;
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
	 * 기간 안에 시작한 스위트 전체 실행의 스위트별 합계 (케이스 단건 실행·여기까지 실행·중지된 실행 제외). 실행 행의 판정 수(V10)만 사용.
	 * 기간 조건은 {@code idx_run_time}
	 */
	@Query("""
			select t.suite.id as suiteId, count(t) as runs,
			       sum(case when t.failedCount + t.errorCount > 0 then 1 else 0 end) as failingRuns,
			       sum(t.passedCount) as passed, sum(t.failedCount) as failed, sum(t.errorCount) as errors
			from TestRun t
			where t.startedAt >= :from and t.startedAt < :to
			  and t.testCaseId is null
			  and t.throughCaseId is null
			  and t.status <> com.sentinel.domain.TestRun.Status.CANCELED
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
	 * 케이스 단건 실행·여기까지 실행·중지된 실행은 제외 (일부 케이스만 통과해도 스위트가 통과한 것처럼 보이지 않도록)
	 */
	@Query("""
			select t from TestRun t
			where t.id in (
			    select max(latest.id) from TestRun latest
			    where latest.testCaseId is null and latest.throughCaseId is null
			      and latest.status <> com.sentinel.domain.TestRun.Status.CANCELED
			    group by latest.suite.id)
			""")
	List<TestRun> findLatestPerSuite();

	/**
	 * 직전 스위트 전체 실행 (같은 스위트·환경, 지정 상태). 케이스 단건 실행·여기까지 실행 제외.
	 * 시작 시각 상한({@code <= startedAt})을 따로 두어 인덱스를 기준 실행의 시작 시각부터 거슬러 읽음 (이력 길이와 무관).
	 * OR 조건만 두면 상한이 인덱스 조건이 되지 못해 최신 실행부터 훑음 (45일치에서 40일 전 실행 기준 29ms → 0.06ms)
	 * @param startedAt 기준 실행의 시작 시각. 같은 시각이면 ID가 작은 실행이 앞선 실행
	 */
	@Query("""
			select t from TestRun t
			where t.suite.id = :suiteId and t.environment.id = :environmentId
			  and t.testCaseId is null and t.throughCaseId is null
			  and t.status = :status
			  and t.startedAt <= :startedAt and (t.startedAt < :startedAt or t.id < :runId)
			order by t.startedAt desc, t.id desc
			""")
	List<TestRun> findPreviousWholeRuns(@Param("suiteId") long suiteId, @Param("environmentId") long environmentId,
			@Param("status") TestRun.Status status, @Param("startedAt") Instant startedAt, @Param("runId") long runId,
			Limit limit);

	/*
	 * 진행 중 실행의 상태·판정 수 변경. 실행 스레드(판정 수·완료)와 중지 요청(상태)이 같은 행을 동시에 바꾸므로
	 * 엔티티 전체를 다시 쓰지 않고 해당 컬럼만 갱신 (한쪽이 다른 쪽의 변경을 덮어쓰지 않도록)
	 */

	@Modifying
	@Query("update TestRun t set t.passedCount = t.passedCount + 1 where t.id = :runId")
	void incrementPassed(@Param("runId") long runId);

	@Modifying
	@Query("update TestRun t set t.failedCount = t.failedCount + 1 where t.id = :runId")
	void incrementFailed(@Param("runId") long runId);

	@Modifying
	@Query("update TestRun t set t.errorCount = t.errorCount + 1 where t.id = :runId")
	void incrementErrors(@Param("runId") long runId);

	/** 진행 중이면 완료 처리 @return 갱신한 행 수. 그사이 중지됐으면 0 */
	@Modifying
	@Query("""
			update TestRun t set t.status = com.sentinel.domain.TestRun.Status.COMPLETED, t.finishedAt = :finishedAt,
			  t.updatedAt = :finishedAt
			where t.id = :runId and t.status = com.sentinel.domain.TestRun.Status.RUNNING
			""")
	int completeIfRunning(@Param("runId") long runId, @Param("finishedAt") Instant finishedAt);

	/** 진행 중이면 중지 처리 @return 갱신한 행 수. 이미 끝났으면 0 */
	@Modifying
	@Query("""
			update TestRun t set t.status = com.sentinel.domain.TestRun.Status.CANCELED, t.finishedAt = :canceledAt,
			  t.canceledBy = :canceledBy, t.updatedAt = :canceledAt
			where t.id = :runId and t.status = com.sentinel.domain.TestRun.Status.RUNNING
			""")
	int cancelIfRunning(@Param("runId") long runId, @Param("canceledBy") long canceledBy,
			@Param("canceledAt") Instant canceledAt);

	@Query("select t.status from TestRun t where t.id = :runId")
	TestRun.Status findStatus(@Param("runId") long runId);

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
