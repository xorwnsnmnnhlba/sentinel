package com.sentinel.api;

import java.util.List;

import com.sentinel.application.CatalogQueryService;
import com.sentinel.application.CatalogQueryService.CaseItem;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/cases")
class CaseController {

	private final CatalogQueryService catalog;

	CaseController(CatalogQueryService catalog) {
		this.catalog = catalog;
	}

	@GetMapping
	List<CaseItem> list(@RequestParam(required = false) Long suiteId) {
		return catalog.listCases(suiteId);
	}

	@GetMapping("/{caseId}")
	CaseItem get(@PathVariable long caseId) {
		return catalog.getCase(caseId);
	}

}
