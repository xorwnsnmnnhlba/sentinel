package com.sentinel.api;

import java.net.URI;
import java.util.List;

import com.sentinel.api.request.CreateNotificationDeliveryRequest;
import com.sentinel.application.NotificationDeliveryService;
import com.sentinel.application.NotificationDeliveryService.DeliveryItem;
import com.sentinel.application.PageResponse;
import com.sentinel.application.auth.AuthenticatedUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 알림 발송 이력과 알림 채널. "알림이 안 왔다"를 구성원도 확인하도록 모든 사용자 조회 (주소는 기록하지 않음)
 */
@RestController
@Tag(name = "알림")
@RequestMapping("/api")
@RequiredArgsConstructor
class NotificationDeliveryController {

	private final NotificationDeliveryService deliveries;

	private final CurrentUser currentUser;

	/**
	 * 최신순. size는 1~100으로 보정
	 * @param projectId 그 프로젝트 Webhook으로 보낸 것만
	 * @param failed true면 보내지 못한 것만
	 */
	@GetMapping("/notification-deliveries")
	PageResponse<DeliveryItem> list(@RequestParam(required = false) Long projectId,
			@RequestParam(defaultValue = "false") boolean failed, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return deliveries.list(projectId, failed, page, size);
	}

	/** 단건 (연결 확인 알림의 Location) */
	@GetMapping("/notification-deliveries/{deliveryId}")
	DeliveryItem get(@PathVariable long deliveryId) {
		return deliveries.get(deliveryId);
	}

	/**
	 * 연결 확인 알림 발송 (만들 수 있는 발송은 연결 확인뿐). 실제 알림과 같은 경로로 보내고 그 발송 기록을 201로 응답.
	 * 보내지 못해도 기록은 남으므로 결과는 본문의 {@code delivered}·{@code statusCode}·{@code error}
	 * <p>
	 * {@code projectId}가 없으면 서버 기본 채널로, 모든 프로젝트에 걸친 채널이라 관리자만.
	 * Webhook·기본 채널 주소가 없으면 409, 프로젝트 주소를 읽을 수 없으면(키 변경) 400
	 */
	@ResponseStatus(HttpStatus.CREATED)
	@PostMapping("/notification-deliveries")
	ResponseEntity<DeliveryItem> sendTest(@RequestBody(required = false) CreateNotificationDeliveryRequest request) {
		Long projectId = request == null ? null : request.projectId();
		AuthenticatedUser user = projectId == null ? currentUser.requireAdmin() : currentUser.get();
		DeliveryItem sent = deliveries.sendTest(projectId, user.id());
		return ResponseEntity.created(URI.create("/api/notification-deliveries/" + sent.id())).body(sent);
	}

	/**
	 * 알림 채널: 서버 기본 채널(첫 항목)과 Webhook을 지정한 프로젝트. 채널마다 최근 7일 안 마지막 발송이 실패면 {@code failing}
	 */
	@GetMapping("/notification-channels")
	List<NotificationDeliveryService.ChannelItem> channels() {
		return deliveries.channels();
	}

}
