package com.sentinel.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import com.sentinel.application.notification.NotificationChannels;
import com.sentinel.application.notification.NotificationProperties;
import com.sentinel.application.secret.SecretDecryptionException;
import com.sentinel.application.secret.SecretKeyMissingException;
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
	 * @throws NotFoundException 미존재 (보관기간이 지나 삭제된 기록 포함)
	 */
	@Transactional(readOnly = true)
	public DeliveryItem get(long deliveryId) {
		NotificationDelivery delivery = deliveries.findById(deliveryId)
			.orElseThrow(() -> new NotFoundException("notification delivery", deliveryId));
		return DeliveryItem.of(delivery,
				delivery.getProjectId() == null ? null : projectNames(List.of(delivery)).get(delivery.getProjectId()));
	}

	/**
	 * 알림 채널: 서버 기본 채널(항상 첫 항목)과 Webhook을 지정한 프로젝트(이름 순)
	 * <p>
	 * 채널마다 최근 7일 안 마지막 발송이 실패면 {@code failing}. 한 번 성공하면 빠짐. 주소가 없는 기본 채널은 판단하지 않음
	 */
	@Transactional(readOnly = true)
	public List<ChannelItem> channels() {
		Map<ChannelKey, Failing> failing = failing();
		boolean defaultConfigured = notificationProperties.hasWebhook();
		List<ChannelItem> items = new ArrayList<>();
		items.add(new ChannelItem(NotificationDelivery.Channel.DEFAULT, null, defaultConfigured,
				channels.defaultFormat(),
				defaultConfigured ? failing.get(new ChannelKey(NotificationDelivery.Channel.DEFAULT, null)) : null));
		projects.findByArchivedAtIsNullAndWebhookUrlCipherIsNotNullOrderByName()
			.forEach(project -> items.add(new ChannelItem(NotificationDelivery.Channel.PROJECT,
					new IdName(project.getId(), project.getName()), true, channels.formatOf(project),
					failing.get(new ChannelKey(NotificationDelivery.Channel.PROJECT, project.getId())))));
		return items;
	}

	/** 최근 7일 안 마지막 발송(로그 기록 제외)이 실패한 채널과 이어진 실패 */
	private Map<ChannelKey, Failing> failing() {
		List<NotificationDelivery> recent = deliveries.findBySentAtGreaterThanEqualAndFormatNotOrderBySentAtDescIdDesc(
				clock.instant().minus(HEALTH_WINDOW), NotificationDelivery.Format.LOG);
		// 최신순 목록을 채널별로 (각 목록도 최신순)
		Map<ChannelKey, List<NotificationDelivery>> byChannel = new LinkedHashMap<>();
		recent.forEach(delivery -> byChannel
			.computeIfAbsent(new ChannelKey(delivery.getChannel(), delivery.getProjectId()), key -> new ArrayList<>())
			.add(delivery));
		Map<ChannelKey, Failing> failing = new HashMap<>();
		byChannel.forEach((key, sent) -> {
			NotificationDelivery last = sent.getFirst();
			if (last.isDelivered()) {
				return;
			}
			int failures = 0;
			while (failures < sent.size() && !sent.get(failures).isDelivered()) {
				failures++;
			}
			failing.put(key, new Failing(sent.get(failures - 1).getSentAt(), failures, last.getSentAt(),
					last.getStatusCode(), last.getError()));
		});
		return failing;
	}

	/**
	 * 연결 확인 알림 발송. 실제 알림과 같은 경로로 보내고 발송 이력에 남긴 그 기록을 반환. 발송은 트랜잭션 밖에서
	 * @param projectId 그 프로젝트 Webhook으로. null이면 서버 기본 채널
	 * @param requesterId 보낸 사람. 알림에 이메일 표시
	 * @throws NotFoundException 프로젝트 미존재
	 * @throws ConflictException Webhook 미지정, 기본 채널 주소 미설정
	 * @throws InvalidRequestException 암호화 키 미설정·불일치로 주소를 읽을 수 없음
	 */
	public DeliveryItem sendTest(Long projectId, long requesterId) {
		String requester = users.findById(requesterId).map(User::getEmail).orElse("알 수 없음");
		Long deliveryId;
		try {
			if (projectId == null) {
				deliveryId = channels.sendDefaultTest(requester);
			}
			else {
				Project project = projects.findByIdAndArchivedAtIsNull(projectId)
					.orElseThrow(() -> new NotFoundException("project", projectId));
				deliveryId = channels.sendTest(project, requester);
			}
		}
		catch (IllegalStateException ex) {
			throw new ConflictException(ex.getMessage());
		}
		catch (SecretKeyMissingException | SecretDecryptionException ex) {
			throw new InvalidRequestException("webhook of project " + projectId + " cannot be read: " + ex.getMessage());
		}
		if (deliveryId == null) {
			// 기록은 별도 트랜잭션. 실패했다면 DB 연결 문제라 응답할 기록이 없음
			throw new IllegalStateException("the test notification was sent but could not be recorded");
		}
		return get(deliveryId);
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
	 * @param project 프로젝트 Webhook이면 그 프로젝트 {id, name}, 기본 채널이면 null
	 * @param configured 주소가 있음. false는 기본 채널뿐 (알림이 서버 로그에만 남음)
	 * @param format 주소로 판별한 서식. 기본 채널 주소가 없으면 LOG, 프로젝트 주소를 읽을 수 없으면 null
	 * @param failing 최근 7일 안 마지막 발송이 실패면 이어진 실패, 아니면 null
	 */
	public record ChannelItem(NotificationDelivery.Channel channel, IdName project, boolean configured,
			NotificationDelivery.Format format, Failing failing) {
	}

	/**
	 * @param since 이어진 실패 중 가장 이른 발송 시각 (최근 7일 안)
	 * @param failures 마지막 성공 이후 실패 건수 (최근 7일 안)
	 * @param statusCode 마지막 실패의 상태 코드. 응답을 받지 못했으면 null
	 * @param error 마지막 실패의 사유 (TIMEOUT 등)
	 */
	public record Failing(Instant since, int failures, Instant lastSentAt, Integer statusCode, String error) {
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
