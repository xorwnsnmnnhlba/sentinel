package com.sentinel.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.sentinel.application.notification.NotificationChannels;
import com.sentinel.application.notification.NotificationProperties;
import com.sentinel.application.notification.WebhookSender;
import com.sentinel.domain.NotificationDelivery;
import com.sentinel.domain.Project;
import com.sentinel.domain.User;
import com.sentinel.infra.persistence.NotificationDeliveryRepository;
import com.sentinel.infra.persistence.ProjectRepository;
import com.sentinel.infra.persistence.UserRepository;
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

	/** 채널 상태를 판단할 기간. 이보다 오래 보내지 않은 채널은 판단하지 않음 */
	static final Duration HEALTH_WINDOW = Duration.ofDays(7);

	private final NotificationDeliveryRepository deliveries;

	private final ProjectRepository projects;

	private final RetentionProperties retention;

	private final NotificationProperties notificationProperties;

	private final NotificationChannels channels;

	private final UserRepository users;

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

	/**
	 * 알림을 보내지 못하는 채널. 채널(기본 채널·프로젝트 Webhook)마다 최근 7일 안 마지막 발송이 실패면 포함, 한 번 성공하면 빠짐.
	 * 지금 쓰지 않는 채널(기본 채널 주소 미설정, Webhook을 지운 프로젝트·삭제된 프로젝트)은 제외. 오래 실패한 채널 먼저
	 */
	@Transactional(readOnly = true)
	public ChannelHealth health() {
		List<NotificationDelivery> recent = deliveries.findBySentAtGreaterThanEqualAndFormatNotOrderBySentAtDescIdDesc(
				clock.instant().minus(HEALTH_WINDOW), NotificationDelivery.Format.LOG);
		List<Long> projectIds = recent.stream()
			.map(NotificationDelivery::getProjectId)
			.filter(Objects::nonNull)
			.distinct()
			.toList();
		Map<Long, Project> webhookProjects = projects.findAllById(projectIds)
			.stream()
			.filter(project -> !project.isArchived() && project.hasWebhook())
			.collect(Collectors.toMap(Project::getId, Function.identity()));
		// 최신순 목록을 채널별로 (각 목록도 최신순)
		Map<ChannelKey, List<NotificationDelivery>> byChannel = new LinkedHashMap<>();
		recent.forEach(delivery -> byChannel
			.computeIfAbsent(new ChannelKey(delivery.getChannel(), delivery.getProjectId()), key -> new ArrayList<>())
			.add(delivery));
		List<FailingChannel> failing = new ArrayList<>();
		for (List<NotificationDelivery> sent : byChannel.values()) {
			NotificationDelivery last = sent.getFirst();
			if (last.isDelivered() || !inUse(last, webhookProjects)) {
				continue;
			}
			int failures = 0;
			while (failures < sent.size() && !sent.get(failures).isDelivered()) {
				failures++;
			}
			Project project = last.getProjectId() == null ? null : webhookProjects.get(last.getProjectId());
			failing.add(new FailingChannel(last.getChannel(),
					project == null ? null : new IdName(project.getId(), project.getName()), last.getFormat(),
					sent.get(failures - 1).getSentAt(), failures, last.getSentAt(), last.getStatusCode(),
					last.getError()));
		}
		failing.sort(Comparator.comparing(FailingChannel::failingSince));
		return new ChannelHealth(notificationProperties.hasWebhook(), failing);
	}

	private boolean inUse(NotificationDelivery delivery, Map<Long, Project> webhookProjects) {
		return delivery.getChannel() == NotificationDelivery.Channel.DEFAULT ? notificationProperties.hasWebhook()
				: webhookProjects.containsKey(delivery.getProjectId());
	}

	/**
	 * 서버 기본 채널로 연결 확인 알림 (배포 직후 기본 채널이 받는지 확인). 발송은 트랜잭션 밖에서
	 * @param requesterId 보낸 사람. 알림에 이메일 표시
	 * @throws ConflictException 기본 채널 주소 미설정
	 */
	public WebhookSender.Delivery sendDefaultTest(long requesterId) {
		String requester = users.findById(requesterId).map(User::getEmail).orElse("알 수 없음");
		try {
			return channels.sendDefaultTest(requester);
		}
		catch (IllegalStateException ex) {
			throw new ConflictException(ex.getMessage());
		}
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

	/** 기본 채널은 projectId null */
	private record ChannelKey(NotificationDelivery.Channel channel, Long projectId) {
	}

	/**
	 * @param defaultChannelConfigured 서버 기본 채널 주소가 있음. false면 기본 채널 알림은 서버 로그에만 남음
	 * @param failing 마지막 발송이 실패한 채널, 오래 실패한 순
	 */
	public record ChannelHealth(boolean defaultChannelConfigured, List<FailingChannel> failing) {
	}

	/**
	 * @param project 프로젝트 Webhook이면 그 프로젝트, 기본 채널이면 null
	 * @param failingSince 이어진 실패 중 가장 이른 발송 시각 (최근 7일 안)
	 * @param failures 마지막 성공 이후 실패 건수 (최근 7일 안)
	 * @param statusCode 마지막 실패의 상태 코드. 응답을 받지 못했으면 null
	 * @param error 마지막 실패의 사유 (TIMEOUT 등)
	 */
	public record FailingChannel(NotificationDelivery.Channel channel, IdName project,
			NotificationDelivery.Format format, Instant failingSince, int failures, Instant lastSentAt,
			Integer statusCode, String error) {
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
