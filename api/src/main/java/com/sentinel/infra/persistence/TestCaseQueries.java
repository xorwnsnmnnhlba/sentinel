package com.sentinel.infra.persistence;

import java.util.Collection;
import java.util.List;

import com.sentinel.domain.TestCase;

/**
 * 케이스 조회 (QueryDSL). 조회 대상은 보관되지 않은 케이스만. 실행 결과의 케이스 참조는 보관 여부와 무관하게 유지
 */
public interface TestCaseQueries {

	/** 스위트의 케이스를 실행 순서로 (순서가 같으면 ID 순) */
	List<TestCase> findActiveInRunOrder(long suiteId);

	/** 모든 케이스를 스위트별 실행 순서로 */
	List<TestCase> findAllActiveInRunOrder();

	/**
	 * 이름·경로 부분 일치 검색 (대소문자 무시). 스위트별 실행 순서
	 * @param pattern 소문자 LIKE 패턴. {@code %}·{@code _}·{@code \}는 {@code \}로 이스케이프
	 * @param suiteId null이면 모든 스위트
	 */
	List<TestCase> search(String pattern, Long suiteId, int limit);

	/** 스위트의 가장 큰 실행 순서. 케이스가 없으면 0. 보관된 케이스도 포함하여 새 케이스가 항상 마지막 */
	int findMaxRunOrder(long suiteId);

	/** 보관되지 않은 케이스를 스위트와 함께 로딩 */
	List<TestCase> findActiveWithSuiteByIdIn(Collection<Long> ids);

}
