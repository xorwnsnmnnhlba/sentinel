package com.sentinel.infra.persistence;

import java.time.Instant;
import java.util.List;

import com.querydsl.core.types.Predicate;
import com.sentinel.domain.TestRun;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * 실행 조회·변경 (QueryDSL)
 */
public interface TestRunQueries {

	/**
	 * 실행 목록 (시작 시각·ID 역순). 스위트·프로젝트·환경을 함께 로딩하여 행마다 추가 쿼리 방지.
	 * 결과가 한 페이지에 모두 들어가면 전체 건수 쿼리 생략
	 * @param condition 조건 ({@code RunFilter}). null이면 전체
	 * @param pageable 페이지 번호·크기 (정렬은 무시)
	 */
	Page<TestRun> findNewestFirst(Predicate condition, Pageable pageable);

	/** 같은 스위트·환경의 이전 실행 (지정한 실행 방식·상태), ID 역순. 실패 연속 여부·복구 판단용 */
	List<TestRun> findPreviousRuns(long suiteId, long environmentId, TestRun.Trigger trigger, TestRun.Status status,
			long beforeRunId, int limit);

	/**
	 * 같은 스위트·환경에서 {@code startedAfter} 이후(미포함) 시작한 이전 실행 중 실패 알림을 보낸 실행이 있는지.
	 * 시작 시각 하한으로 인덱스(idx_run_suite_time)에서 간격 안의 실행만 읽음
	 */
	boolean existsFailureNotifiedSince(long suiteId, long environmentId, Instant startedAfter, long beforeRunId);

	/**
	 * 직전 스위트 전체 실행 (같은 스위트·환경, 지정 상태). 케이스 단건 실행·여기까지 실행·실패 다시 실행 제외.
	 * 시작 시각 상한({@code <= startedAt})을 따로 두어 인덱스를 기준 실행의 시작 시각부터 거슬러 읽음 (이력 길이와 무관).
	 * OR 조건만 두면 상한이 인덱스 조건이 되지 못해 최신 실행부터 훑음 (45일치에서 40일 전 실행 기준 29ms → 0.06ms)
	 * @param startedAt 기준 실행의 시작 시각. 같은 시각이면 ID가 작은 실행이 앞선 실행
	 */
	List<TestRun> findPreviousWholeRuns(long suiteId, long environmentId, TestRun.Status status, Instant startedAt,
			long runId, int limit);

	/**
	 * 원문 정리 대상. 원문이 남아 있고 {@code cutoff} 이전에 시작한, 지정 상태가 아닌 실행을 오래된 순으로
	 * ({@code idx_run_raw_purge})
	 */
	List<TestRun> findRawPurgeTargets(Instant cutoff, TestRun.Status excluded, int limit);

	/**
	 * 기간 안에 시작한 스위트 전체 실행의 스위트별 합계 (케이스 단건 실행·여기까지 실행·실패 다시 실행·중지된 실행 제외). 실행 행의 판정 수(V10)만 사용.
	 * 기간 조건은 {@code idx_run_time}
	 */
	List<SuiteRunTotals> summarizeBySuite(Instant from, Instant to);

	/**
	 * 스위트별 가장 최근 스위트 전체 실행. ID는 시작 순으로 증가하므로 최대 ID가 최근 실행
	 * 케이스 단건 실행·여기까지 실행·실패 다시 실행·중지된 실행은 제외 (일부 케이스만 통과해도 스위트가 통과한 것처럼 보이지 않도록)
	 */
	List<TestRun> findLatestPerSuite();

	TestRun.Status findStatus(long runId);

	/*
	 * 진행 중 실행의 상태·판정 수 변경. 실행 스레드(판정 수·완료)와 중지 요청(상태)이 같은 행을 동시에 바꾸므로
	 * 엔티티 전체를 다시 쓰지 않고 해당 컬럼만 갱신 (한쪽이 다른 쪽의 변경을 덮어쓰지 않도록). 트랜잭션 안에서 호출
	 */

	void incrementPassed(long runId);

	void incrementFailed(long runId);

	void incrementErrors(long runId);

	/** 응답을 받지 못해 다시 보낸 결과 1건 추가 */
	void incrementRetried(long runId);

	/** 진행 중이면 완료 처리 @return 갱신한 행 수. 그사이 중지됐으면 0 */
	long completeIfRunning(long runId, Instant finishedAt);

	/** 진행 중이면 중지 처리 @return 갱신한 행 수. 이미 끝났으면 0 */
	long cancelIfRunning(long runId, long canceledBy, Instant canceledAt);

	record SuiteRunTotals(long suiteId, long runs, long failingRuns, long passed, long failed, long errors) {
	}

}
