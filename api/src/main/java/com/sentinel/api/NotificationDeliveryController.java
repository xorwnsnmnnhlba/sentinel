package com.sentinel.api;

import com.sentinel.application.NotificationDeliveryService;
import com.sentinel.application.NotificationDeliveryService.DeliveryItem;
import com.sentinel.application.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 알림 발송 이력. "알림이 안 왔다"를 구성원도 확인하도록 모든 사용자 조회 (주소는 기록하지 않음)
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
class NotificationDeliveryController {

	private final NotificationDeliveryService deliveries;

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

	/**
	 * 알림을 보내지 못하는 채널 (대시보드 경고용). 채널마다 최근 7일 안 마지막 발송이 실패면 포함
	 */
	@GetMapping("/notification-channel-health")
	NotificationDeliveryService.ChannelHealth health() {
		return deliveries.health();
	}

}
