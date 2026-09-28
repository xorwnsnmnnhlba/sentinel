package com.sentinel.infra.persistence;

import java.time.Instant;
import java.util.List;

import com.querydsl.core.types.Predicate;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.CaseBuilder;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.sentinel.domain.QTestRun;
import com.sentinel.domain.TestRun;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.support.PageableExecutionUtils;

class TestRunQueriesImpl implements TestRunQueries {

	private static final QTestRun run = QTestRun.testRun;

	private final JPAQueryFactory queries;

	/** 공유 EntityManager(트랜잭션별 위임 프록시)로 생성. 설정 빈 없이 슬라이스 테스트({@code @DataJpaTest})에서도 동작 */
	TestRunQueriesImpl(EntityManager entityManager) {
		this.queries = new JPAQueryFactory(entityManager);
	}

	@Override
	public Page<TestRun> findNewestFirst(Predicate condition, Pageable pageable) {
		List<TestRun> content = queries.selectFrom(run)
			.join(run.suite)
			.fetchJoin()
			.join(run.suite.project)
			.fetchJoin()
			.join(run.environment)
			.fetchJoin()
			.where(condition)
			.orderBy(run.startedAt.desc(), run.id.desc())
			.offset(pageable.getOffset())
			.limit(pageable.getPageSize())
			.fetch();
		return PageableExecutionUtils.getPage(content, pageable,
				() -> queries.select(run.count()).from(run).where(condition).fetchOne());
	}

	@Override
	public List<TestRun> findPreviousRuns(long suiteId, long environmentId, TestRun.Trigger trigger,
			TestRun.Status status, long beforeRunId, int limit) {
		return queries.selectFrom(run)
			.where(run.suite.id.eq(suiteId), run.environment.id.eq(environmentId), run.trigger.eq(trigger),
					run.status.eq(status), run.id.lt(beforeRunId))
			.orderBy(run.id.desc())
			.limit(limit)
			.fetch();
	}

	@Override
	public List<TestRun> findPreviousWholeRuns(long suiteId, long environmentId, TestRun.Status status,
			Instant startedAt, long runId, int limit) {
		return queries.selectFrom(run)
			.where(run.suite.id.eq(suiteId), run.environment.id.eq(environmentId), wholeSuite(),
					run.status.eq(status), run.startedAt.loe(startedAt),
					run.startedAt.lt(startedAt).or(run.id.lt(runId)))
			.orderBy(run.startedAt.desc(), run.id.desc())
			.limit(limit)
			.fetch();
	}

	@Override
	public List<TestRun> findRawPurgeTargets(Instant cutoff, TestRun.Status excluded, int limit) {
		return queries.selectFrom(run)
			.where(run.rawPurgedAt.isNull(), run.startedAt.lt(cutoff), run.status.ne(excluded))
			.orderBy(run.startedAt.asc())
			.limit(limit)
			.fetch();
	}

	@Override
	public List<SuiteRunTotals> summarizeBySuite(Instant from, Instant to) {
		return queries
			.select(Projections.constructor(SuiteRunTotals.class, run.suite.id, run.count(),
					new CaseBuilder().when(run.failedCount.add(run.errorCount).gt(0)).then(1L).otherwise(0L).sumLong(),
					run.passedCount.sumLong(), run.failedCount.sumLong(), run.errorCount.sumLong()))
			.from(run)
			.where(run.startedAt.goe(from), run.startedAt.lt(to), wholeSuite(), run.status.ne(TestRun.Status.CANCELED))
			.groupBy(run.suite.id)
			.fetch();
	}

	@Override
	public List<TestRun> findLatestPerSuite() {
		QTestRun latest = new QTestRun("latest");
		return queries.selectFrom(run)
			.where(run.id.in(JPAExpressions.select(latest.id.max())
				.from(latest)
				.where(latest.testCaseId.isNull(), latest.throughCaseId.isNull(),
						latest.status.ne(TestRun.Status.CANCELED))
				.groupBy(latest.suite.id)))
			.fetch();
	}

	@Override
	public TestRun.Status findStatus(long runId) {
		return queries.select(run.status).from(run).where(run.id.eq(runId)).fetchOne();
	}

	@Override
	public void incrementPassed(long runId) {
		queries.update(run).set(run.passedCount, run.passedCount.add(1)).where(run.id.eq(runId)).execute();
	}

	@Override
	public void incrementFailed(long runId) {
		queries.update(run).set(run.failedCount, run.failedCount.add(1)).where(run.id.eq(runId)).execute();
	}

	@Override
	public void incrementErrors(long runId) {
		queries.update(run).set(run.errorCount, run.errorCount.add(1)).where(run.id.eq(runId)).execute();
	}

	@Override
	public long completeIfRunning(long runId, Instant finishedAt) {
		return queries.update(run)
			.set(run.status, TestRun.Status.COMPLETED)
			.set(run.finishedAt, finishedAt)
			.set(run.updatedAt, finishedAt)
			.where(run.id.eq(runId), run.status.eq(TestRun.Status.RUNNING))
			.execute();
	}

	@Override
	public long cancelIfRunning(long runId, long canceledBy, Instant canceledAt) {
		return queries.update(run)
			.set(run.status, TestRun.Status.CANCELED)
			.set(run.finishedAt, canceledAt)
			.set(run.canceledBy, canceledBy)
			.set(run.updatedAt, canceledAt)
			.where(run.id.eq(runId), run.status.eq(TestRun.Status.RUNNING))
			.execute();
	}

	/** 스위트 전체 실행 (케이스 단건 실행·여기까지 실행 제외) */
	private static BooleanExpression wholeSuite() {
		return run.testCaseId.isNull().and(run.throughCaseId.isNull());
	}

}
