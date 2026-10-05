package com.sentinel.infra.persistence;

import com.sentinel.domain.NotificationDelivery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * 알림 발송 이력 조회 (QueryDSL)
 */
public interface NotificationDeliveryQueries {

	/**
	 * 발송 시각 최신순 (같은 시각은 나중 기록 먼저)
	 * @param projectId 그 프로젝트 Webhook으로 보낸 것만. null이면 전체
	 * @param failedOnly 보내지 못한 것만
	 * @param pageable 페이지 번호·크기 (정렬은 무시)
	 */
	Page<NotificationDelivery> findNewestFirst(Long projectId, boolean failedOnly, Pageable pageable);

}
