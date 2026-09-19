package com.sentinel.api;

import java.util.List;

import com.sentinel.application.CatalogCommandService;
import com.sentinel.application.CatalogQueryService;
import com.sentinel.application.CatalogQueryService.SuiteItem;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/suites")
class SuiteController {

	private final CatalogQueryService catalog;

	private final CatalogCommandService commands;

	SuiteController(CatalogQueryService catalog, CatalogCommandService commands) {
		this.catalog = catalog;
		this.commands = commands;
	}

	@GetMapping
	List<SuiteItem> list(@RequestParam(required = false) Long projectId) {
		return catalog.listSuites(projectId);
	}

	@GetMapping("/{suiteId}")
	SuiteItem get(@PathVariable long suiteId) {
		return catalog.getSuite(suiteId);
	}

	/**
	 * 삭제(보관). 하위 항목 연쇄 보관, 실행 이력은 유지
	 */
	@DeleteMapping("/{suiteId}")
	ResponseEntity<Void> delete(@PathVariable long suiteId) {
		commands.archiveSuite(suiteId);
		return ResponseEntity.noContent().build();
	}

}
