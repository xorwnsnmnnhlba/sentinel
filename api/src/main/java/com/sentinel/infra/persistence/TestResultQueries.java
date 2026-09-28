package com.sentinel.infra.persistence;

import java.util.Collection;
import java.util.List;

import com.sentinel.domain.TestResult;

/**
 * 결과 조회·변경 (QueryDSL). 백분위·LATERAL 등 SQL 표준 기능이 필요한 집계는 {@link TestResultRepository}의 네이티브 쿼리
 */
public interface TestResultQueries {

	/** 결과 상세용. 케이스 이름 조회 시 결과 건수만큼 추가 쿼리가 발생하지 않도록 함께 로딩 */
	List<TestResult> findWithTestCaseByRunId(long runId);

	/**
	 * 요청·응답 헤더와 본문을 일괄로 비움. 판정·규칙별 결과·상태 코드·URL·소요 시간은 유지
	 * @return 갱신한 결과 건수
	 */
	long purgeRawByRunIds(Collection<Long> runIds);

	/** 실행별 케이스 판정. 연속 실패·직전 실행 대비 변화 계산용 */
	List<CaseVerdict> findCaseVerdictsByRunIds(Collection<Long> runIds);

	/** @param verdict 판정하지 못한 결과(오류)는 null */
	record CaseVerdict(long runId, long caseId, TestResult.Verdict verdict) {
	}

}
