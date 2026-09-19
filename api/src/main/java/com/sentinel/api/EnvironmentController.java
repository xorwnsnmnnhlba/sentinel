package com.sentinel.api;

import java.util.List;

import com.sentinel.application.CatalogCommandService;
import com.sentinel.application.CatalogQueryService;
import com.sentinel.application.CatalogQueryService.EnvironmentItem;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/environments")
class EnvironmentController {

	private final CatalogQueryService catalog;

	private final CatalogCommandService commands;

	EnvironmentController(CatalogQueryService catalog, CatalogCommandService commands) {
		this.catalog = catalog;
		this.commands = commands;
	}

	@GetMapping
	List<EnvironmentItem> list(@RequestParam(required = false) Long projectId) {
		return catalog.listEnvironments(projectId);
	}

	@GetMapping("/{environmentId}")
	EnvironmentItem get(@PathVariable long environmentId) {
		return catalog.getEnvironment(environmentId);
	}

	/**
	 * 삭제(보관). 하위 항목 연쇄 보관, 실행 이력은 유지
	 */
	@DeleteMapping("/{environmentId}")
	ResponseEntity<Void> delete(@PathVariable long environmentId) {
		commands.archiveEnvironment(environmentId);
		return ResponseEntity.noContent().build();
	}

}
