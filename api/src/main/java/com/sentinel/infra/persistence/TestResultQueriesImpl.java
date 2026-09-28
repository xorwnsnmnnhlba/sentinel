package com.sentinel.infra.persistence;

import java.util.Collection;
import java.util.List;

import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.sentinel.domain.QTestResult;
import com.sentinel.domain.TestResult;
import jakarta.persistence.EntityManager;

class TestResultQueriesImpl implements TestResultQueries {

	private static final QTestResult result = QTestResult.testResult;

	private final JPAQueryFactory queries;

	/** 공유 EntityManager(트랜잭션별 위임 프록시)로 생성. 설정 빈 없이 슬라이스 테스트({@code @DataJpaTest})에서도 동작 */
	TestResultQueriesImpl(EntityManager entityManager) {
		this.queries = new JPAQueryFactory(entityManager);
	}

	@Override
	public List<TestResult> findWithTestCaseByRunId(long runId) {
		return queries.selectFrom(result)
			.join(result.testCase)
			.fetchJoin()
			.where(result.run.id.eq(runId))
			.orderBy(result.id.asc())
			.fetch();
	}

	@Override
	public long purgeRawByRunIds(Collection<Long> runIds) {
		return queries.update(result)
			.setNull(result.requestHeaders)
			.setNull(result.requestBody)
			.setNull(result.responseHeaders)
			.setNull(result.responseBody)
			.where(result.run.id.in(runIds))
			.execute();
	}

	@Override
	public List<CaseVerdict> findCaseVerdictsByRunIds(Collection<Long> runIds) {
		return queries
			.select(Projections.constructor(CaseVerdict.class, result.run.id, result.testCase.id, result.verdict))
			.from(result)
			.where(result.run.id.in(runIds))
			.fetch();
	}

}
