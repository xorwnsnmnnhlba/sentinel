package com.sentinel.api;

import java.util.List;

import com.sentinel.application.PendingSchemaChangeService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "실행")
@RequiredArgsConstructor
class PendingSchemaChangeController {

	private final PendingSchemaChangeService pendingSchemaChanges;

	/**
	 * 승인 대기 구조 변화. 케이스·환경별 마지막 구조 지문이 승인 대기인 결과, 가장 심한 등급 순 (같으면 최근 실행 순)
	 */
	@GetMapping("/api/pending-schema-changes")
	List<PendingSchemaChangeService.PendingSchemaChange> list() {
		return pendingSchemaChanges.list();
	}

}
