package com.sentinel.api;

import java.util.List;

import com.sentinel.application.CatalogQueryService;
import com.sentinel.application.CatalogQueryService.SuiteItem;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/suites")
class SuiteController {

	private final CatalogQueryService catalog;

	SuiteController(CatalogQueryService catalog) {
		this.catalog = catalog;
	}

	@GetMapping
	List<SuiteItem> list(@RequestParam(required = false) Long projectId) {
		return catalog.listSuites(projectId);
	}

	@GetMapping("/{suiteId}")
	SuiteItem get(@PathVariable long suiteId) {
		return catalog.getSuite(suiteId);
	}

}
