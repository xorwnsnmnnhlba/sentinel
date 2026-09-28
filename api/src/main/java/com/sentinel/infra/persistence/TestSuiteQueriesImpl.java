package com.sentinel.infra.persistence;

import java.util.List;

import com.querydsl.core.types.Predicate;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.sentinel.domain.QTestCase;
import com.sentinel.domain.QTestSuite;
import com.sentinel.domain.TestSuite;
import jakarta.persistence.EntityManager;

class TestSuiteQueriesImpl implements TestSuiteQueries {

	private static final QTestSuite suite = QTestSuite.testSuite;

	private static final QTestCase testCase = QTestCase.testCase;

	private final JPAQueryFactory queries;

	/** 공유 EntityManager(트랜잭션별 위임 프록시)로 생성. 설정 빈 없이 슬라이스 테스트({@code @DataJpaTest})에서도 동작 */
	TestSuiteQueriesImpl(EntityManager entityManager) {
		this.queries = new JPAQueryFactory(entityManager);
	}

	@Override
	public List<TestSuite> findActiveWithProject() {
		return queries.selectFrom(suite)
			.join(suite.project)
			.fetchJoin()
			.where(suite.archivedAt.isNull())
			.orderBy(suite.project.name.asc(), suite.name.asc())
			.fetch();
	}

	@Override
	public List<SuiteCaseCount> countCasesByProjectId(long projectId) {
		return countCases(testCase.suite.project.id.eq(projectId));
	}

	@Override
	public List<SuiteCaseCount> countCases() {
		return countCases(null);
	}

	private List<SuiteCaseCount> countCases(Predicate condition) {
		return queries.select(Projections.constructor(SuiteCaseCount.class, testCase.suite.id, testCase.count()))
			.from(testCase)
			.where(testCase.archivedAt.isNull(), condition)
			.groupBy(testCase.suite.id)
			.fetch();
	}

}
