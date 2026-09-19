package com.sentinel.api;

import java.util.List;

import com.sentinel.application.CatalogQueryService;
import com.sentinel.application.CatalogQueryService.EnvironmentItem;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/environments")
class EnvironmentController {

	private final CatalogQueryService catalog;

	EnvironmentController(CatalogQueryService catalog) {
		this.catalog = catalog;
	}

	@GetMapping
	List<EnvironmentItem> list(@RequestParam(required = false) Long projectId) {
		return catalog.listEnvironments(projectId);
	}

	@GetMapping("/{environmentId}")
	EnvironmentItem get(@PathVariable long environmentId) {
		return catalog.getEnvironment(environmentId);
	}

}
