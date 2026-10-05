package com.sentinel.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import com.sentinel.domain.NotificationDelivery;
import com.sentinel.domain.Project;
import com.sentinel.infra.persistence.NotificationDeliveryRepository;
import com.sentinel.infra.persistence.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 알림 발송 이력 조회·정리. 기록은 발송할 때 알림 채널이 남김 ({@code notification.NotificationDeliveryRecorder})
 */
@Service
@RequiredArgsConstructor
public class NotificationDeliveryService {

	private static final Logger log = LoggerFactory.getLogger(NotificationDeliveryService.class);

	public static final int MAX_PAGE_SIZE = 100;

	private final NotificationDeliveryRepository deliveries;

	private final ProjectRepository projects;

	private final RetentionProperties retention;

	private final Clock clock;

	/**
	 * 최신순 목록
	 * @param projectId 그 프로젝트 Webhook으로 보낸 것만. null이면 전체
	 * @param failedOnly 보내지 못한 것만
	 * @param page 0부터 시작, 음수는 0으로 보정
	 * @param size 1~{@link #MAX_PAGE_SIZE}로 보정
	 */
	@Transactional(readOnly = true)
	public PageResponse<DeliveryItem> list(Long projectId, boolean failedOnly, int page, int size) {
		PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE));
		Page<NotificationDelivery> found = deliveries.findNewestFirst(projectId, failedOnly, pageable);
		Map<Long, IdName> names = projectNames(found.getContent());
		return PageResponse.of(found, found.getContent()
			.stream()
			.map(delivery -> DeliveryItem.of(delivery,
					delivery.getProjectId() == null ? null : names.get(delivery.getProjectId())))
			.toList());
	}

	/** 보관기간({@code sentinel.retention.notification-retention})이 지난 기록 삭제 */
	@Transactional
	public int deleteExpired() {
		Instant cutoff = clock.instant().minus(retention.notificationRetention());
		int deleted = deliveries.deleteSentBefore(cutoff);
		if (deleted > 0) {
			log.info("Deleted {} notification delivery record(s) sent before {}", deleted, cutoff);
		}
		return deleted;
	}

	/** 프로젝트 이름. 삭제(보관)된 프로젝트도 조회 */
	private Map<Long, IdName> projectNames(List<NotificationDelivery> found) {
		List<Long> ids = found.stream()
			.map(NotificationDelivery::getProjectId)
			.filter(Objects::nonNull)
			.distinct()
			.toList();
		if (ids.isEmpty()) {
			return Map.of();
		}
		return projects.findAllById(ids)
			.stream()
			.collect(Collectors.toMap(Project::getId, project -> new IdName(project.getId(), project.getName())));
	}

	/**
	 * @param project 프로젝트 Webhook으로 보냈으면 그 프로젝트 {id, name}, 기본 채널이면 null
	 * @param statusCode 받은 응답의 상태 코드. 응답을 받지 못했거나 로그 기록이면 null
	 * @param error 응답을 받지 못한 사유 (TIMEOUT, CONNECTION_FAILED, BLOCKED_DESTINATION 등)
	 */
	public record DeliveryItem(long id, Instant sentAt, NotificationDelivery.Channel channel, IdName project,
			NotificationDelivery.Format format, String title, NotificationDelivery.Tone tone, boolean delivered,
			Integer statusCode, String error) {

		static DeliveryItem of(NotificationDelivery delivery, IdName project) {
			return new DeliveryItem(delivery.getId(), delivery.getSentAt(), delivery.getChannel(), project,
					delivery.getFormat(), delivery.getTitle(), delivery.getTone(), delivery.isDelivered(),
					delivery.getStatusCode(), delivery.getError());
		}

	}

}
