package com.sentinel.infra.persistence;

import java.util.Collection;
import java.util.List;

import com.querydsl.jpa.impl.JPAQueryFactory;
import com.sentinel.domain.QTestCase;
import com.sentinel.domain.TestCase;
import jakarta.persistence.EntityManager;

class TestCaseQueriesImpl implements TestCaseQueries {

	private static final QTestCase testCase = QTestCase.testCase;

	/** LIKE 이스케이프 문자 (검색어의 %·_를 문자 그대로 찾기 위함) */
	private static final char ESCAPE = '\\';

	private final JPAQueryFactory queries;

	/** 공유 EntityManager(트랜잭션별 위임 프록시)로 생성. 설정 빈 없이 슬라이스 테스트({@code @DataJpaTest})에서도 동작 */
	TestCaseQueriesImpl(EntityManager entityManager) {
		this.queries = new JPAQueryFactory(entityManager);
	}

	@Override
	public List<TestCase> findActiveInRunOrder(long suiteId) {
		return queries.selectFrom(testCase)
			.where(testCase.suite.id.eq(suiteId), testCase.archivedAt.isNull())
			.orderBy(testCase.runOrder.asc(), testCase.id.asc())
			.fetch();
	}

	@Override
	public List<TestCase> findAllActiveInRunOrder() {
		return queries.selectFrom(testCase)
			.where(testCase.archivedAt.isNull())
			.orderBy(testCase.suite.id.asc(), testCase.runOrder.asc(), testCase.id.asc())
			.fetch();
	}

	@Override
	public List<TestCase> search(String pattern, Long suiteId, int limit) {
		return queries.selectFrom(testCase)
			.where(testCase.archivedAt.isNull(), suiteId == null ? null : testCase.suite.id.eq(suiteId),
					testCase.name.lower()
						.like(pattern, ESCAPE)
						.or(testCase.request.path.lower().like(pattern, ESCAPE)))
			.orderBy(testCase.suite.id.asc(), testCase.runOrder.asc(), testCase.id.asc())
			.limit(limit)
			.fetch();
	}

	@Override
	public int findMaxRunOrder(long suiteId) {
		Integer max = queries.select(testCase.runOrder.max())
			.from(testCase)
			.where(testCase.suite.id.eq(suiteId))
			.fetchOne();
		return max == null ? 0 : max;
	}

	@Override
	public List<TestCase> findActiveWithSuiteByIdIn(Collection<Long> ids) {
		return queries.selectFrom(testCase)
			.join(testCase.suite)
			.fetchJoin()
			.where(testCase.id.in(ids), testCase.archivedAt.isNull())
			.fetch();
	}

}
