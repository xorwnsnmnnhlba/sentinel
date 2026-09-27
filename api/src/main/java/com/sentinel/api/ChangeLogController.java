package com.sentinel.api;

import com.sentinel.application.ChangeLogService;
import com.sentinel.application.ChangeLogService.ChangeLogItem;
import com.sentinel.application.PageResponse;
import com.sentinel.domain.ChangeLog;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 설정 변경 이력 조회. 기록은 각 등록·수정·삭제 API가 같은 트랜잭션에서 남김
 */
@RestController
@RequestMapping("/api/change-logs")
@RequiredArgsConstructor
class ChangeLogController {

	private final ChangeLogService changeLogs;

	/**
	 * 최신순 목록. size는 1~100으로 보정, 지정하지 않은 필터는 조건에서 제외
	 *
	 * @param targetType targetId와 함께 지정하면 항목 1건의 이력
	 * @param projectId 프로젝트와 그 안의 환경·스위트·케이스·스케줄 이력
	 */
	@GetMapping
	PageResponse<ChangeLogItem> list(@RequestParam(required = false) ChangeLog.TargetType targetType,
			@RequestParam(required = false) Long targetId, @RequestParam(required = false) Long projectId,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		return changeLogs.list(new ChangeLogService.Filter(targetType, targetId, projectId), page, size);
	}

}
