package com.sentinel.infra.persistence;

import java.util.List;

import com.querydsl.core.types.Predicate;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.sentinel.domain.ChangeLog;
import com.sentinel.domain.QChangeLog;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.support.PageableExecutionUtils;

class ChangeLogQueriesImpl implements ChangeLogQueries {

	private static final QChangeLog log = QChangeLog.changeLog;

	private final JPAQueryFactory queries;

	/** 공유 EntityManager(트랜잭션별 위임 프록시)로 생성. 설정 빈 없이 슬라이스 테스트({@code @DataJpaTest})에서도 동작 */
	ChangeLogQueriesImpl(EntityManager entityManager) {
		this.queries = new JPAQueryFactory(entityManager);
	}

	@Override
	public Page<ChangeLog> findNewestFirst(Predicate condition, Pageable pageable) {
		List<ChangeLog> content = queries.selectFrom(log)
			.where(condition)
			.orderBy(log.id.desc())
			.offset(pageable.getOffset())
			.limit(pageable.getPageSize())
			.fetch();
		return PageableExecutionUtils.getPage(content, pageable,
				() -> queries.select(log.count()).from(log).where(condition).fetchOne());
	}

}
