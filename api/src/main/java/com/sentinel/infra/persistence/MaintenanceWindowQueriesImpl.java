package com.sentinel.infra.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.querydsl.jpa.impl.JPAQueryFactory;
import com.sentinel.domain.MaintenanceWindow;
import com.sentinel.domain.QMaintenanceWindow;
import jakarta.persistence.EntityManager;

class MaintenanceWindowQueriesImpl implements MaintenanceWindowQueries {

	private static final QMaintenanceWindow window = QMaintenanceWindow.maintenanceWindow;

	private final JPAQueryFactory queries;

	/** 공유 EntityManager(트랜잭션별 위임 프록시)로 생성. 설정 빈 없이 슬라이스 테스트({@code @DataJpaTest})에서도 동작 */
	MaintenanceWindowQueriesImpl(EntityManager entityManager) {
		this.queries = new JPAQueryFactory(entityManager);
	}

	@Override
	public Optional<MaintenanceWindow> findActive(long environmentId, Instant now) {
		return Optional.ofNullable(queries.selectFrom(window)
			.where(window.environment.id.eq(environmentId), window.archivedAt.isNull(), window.endsAt.gt(now),
					window.startsAt.loe(now))
			.orderBy(window.endsAt.desc())
			.limit(1)
			.fetchOne());
	}

	@Override
	public List<MaintenanceWindow> findOpen(Long environmentId, Instant now) {
		return queries.selectFrom(window)
			.join(window.environment)
			.fetchJoin()
			.where(environmentId == null ? null : window.environment.id.eq(environmentId), window.archivedAt.isNull(),
					window.endsAt.gt(now), window.environment.archivedAt.isNull())
			.orderBy(window.startsAt.asc(), window.id.asc())
			.fetch();
	}

}
