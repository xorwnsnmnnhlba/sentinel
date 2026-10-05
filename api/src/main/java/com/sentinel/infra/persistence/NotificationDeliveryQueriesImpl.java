package com.sentinel.infra.persistence;

import java.util.List;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.sentinel.domain.NotificationDelivery;
import com.sentinel.domain.QNotificationDelivery;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.support.PageableExecutionUtils;

class NotificationDeliveryQueriesImpl implements NotificationDeliveryQueries {

	private static final QNotificationDelivery delivery = QNotificationDelivery.notificationDelivery;

	private final JPAQueryFactory queries;

	NotificationDeliveryQueriesImpl(EntityManager entityManager) {
		this.queries = new JPAQueryFactory(entityManager);
	}

	@Override
	public Page<NotificationDelivery> findNewestFirst(Long projectId, boolean failedOnly, Pageable pageable) {
		BooleanBuilder condition = new BooleanBuilder();
		if (projectId != null) {
			condition.and(delivery.projectId.eq(projectId));
		}
		if (failedOnly) {
			condition.and(delivery.delivered.isFalse());
		}
		List<NotificationDelivery> content = queries.selectFrom(delivery)
			.where(condition)
			.orderBy(delivery.sentAt.desc(), delivery.id.desc())
			.offset(pageable.getOffset())
			.limit(pageable.getPageSize())
			.fetch();
		return PageableExecutionUtils.getPage(content, pageable,
				() -> queries.select(delivery.count()).from(delivery).where(condition).fetchOne());
	}

}
