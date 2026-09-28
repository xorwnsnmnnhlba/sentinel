package com.sentinel.infra.persistence;

import java.util.List;

import com.sentinel.domain.TestSuite;

/** 스위트 조회 (QueryDSL) */
public interface TestSuiteQueries {

	/** 보관되지 않은 스위트를 프로젝트와 함께 로딩 (프로젝트·스위트 이름 순) */
	List<TestSuite> findActiveWithProject();

	/** 프로젝트 내 스위트별 사용 중인 케이스 수. 케이스가 없는 스위트는 결과에 없음 */
	List<SuiteCaseCount> countCasesByProjectId(long projectId);

	/** 모든 스위트별 사용 중인 케이스 수 */
	List<SuiteCaseCount> countCases();

	record SuiteCaseCount(long suiteId, long count) {
	}

}
