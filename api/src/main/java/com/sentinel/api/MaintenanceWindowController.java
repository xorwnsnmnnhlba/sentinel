package com.sentinel.api;

import java.net.URI;
import java.util.List;

import com.sentinel.api.request.CreateMaintenanceWindowRequest;
import com.sentinel.api.request.UpdateMaintenanceWindowRequest;
import com.sentinel.application.MaintenanceWindowItem;
import com.sentinel.application.MaintenanceWindowService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 환경 점검 시간. 점검 중에는 그 환경의 스케줄 실행을 건너뜀 (실행 기록·실패 알림 없음)
 */
@RestController
@RequestMapping("/api/maintenance-windows")
@RequiredArgsConstructor
class MaintenanceWindowController {

	private final MaintenanceWindowService maintenanceWindowService;

	private final CurrentUser currentUser;

	/** 끝나지 않은 점검 (진행 중·예정), 시작 순 */
	@GetMapping
	List<MaintenanceWindowItem> list(@RequestParam(required = false) Long environmentId) {
		return maintenanceWindowService.listOpen(environmentId);
	}

	@PostMapping
	ResponseEntity<MaintenanceWindowItem> create(@Valid @RequestBody CreateMaintenanceWindowRequest request) {
		MaintenanceWindowItem window = maintenanceWindowService.create(request.environmentId(), request.startsAt(),
				request.endsAt(), request.reason(), currentUser.get().id());
		return ResponseEntity.created(URI.create("/api/maintenance-windows/" + window.id())).body(window);
	}

	/** 부분 변경 (연장·단축·사유). 이미 끝난 점검은 409 */
	/** 단건. 끝난 점검도 조회 */
	@GetMapping("/{windowId}")
	MaintenanceWindowItem get(@PathVariable long windowId) {
		return maintenanceWindowService.get(windowId);
	}

	@PatchMapping("/{windowId}")
	MaintenanceWindowItem update(@PathVariable long windowId, @RequestBody UpdateMaintenanceWindowRequest request) {
		return maintenanceWindowService.update(windowId, request.startsAt(), request.endsAt(), request.reason(),
				currentUser.get().id());
	}

	/** 삭제. 예정된 점검은 취소, 진행 중인 점검은 바로 끝남 */
	@DeleteMapping("/{windowId}")
	ResponseEntity<Void> delete(@PathVariable long windowId) {
		maintenanceWindowService.delete(windowId, currentUser.get().id());
		return ResponseEntity.noContent().build();
	}

}
