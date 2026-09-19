package com.sentinel.api;

import java.util.List;

import com.sentinel.application.CatalogCommandService;
import com.sentinel.application.CatalogQueryService;
import com.sentinel.application.CatalogQueryService.ProjectItem;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects")
class ProjectController {

	private final CatalogQueryService catalog;

	private final CatalogCommandService commands;

	ProjectController(CatalogQueryService catalog, CatalogCommandService commands) {
		this.catalog = catalog;
		this.commands = commands;
	}

	@GetMapping
	List<ProjectItem> list() {
		return catalog.listProjects();
	}

	@GetMapping("/{projectId}")
	ProjectItem get(@PathVariable long projectId) {
		return catalog.getProject(projectId);
	}

	/**
	 * 삭제(보관). 하위 항목 연쇄 보관, 실행 이력은 유지
	 */
	@DeleteMapping("/{projectId}")
	ResponseEntity<Void> delete(@PathVariable long projectId) {
		commands.archiveProject(projectId);
		return ResponseEntity.noContent().build();
	}

}
