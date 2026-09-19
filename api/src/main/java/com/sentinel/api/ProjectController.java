package com.sentinel.api;

import java.util.List;

import com.sentinel.application.CatalogQueryService;
import com.sentinel.application.CatalogQueryService.ProjectItem;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects")
class ProjectController {

	private final CatalogQueryService catalog;

	ProjectController(CatalogQueryService catalog) {
		this.catalog = catalog;
	}

	@GetMapping
	List<ProjectItem> list() {
		return catalog.listProjects();
	}

	@GetMapping("/{projectId}")
	ProjectItem get(@PathVariable long projectId) {
		return catalog.getProject(projectId);
	}

}
