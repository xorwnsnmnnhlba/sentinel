package com.sentinel.api;

import java.util.List;

import com.sentinel.application.CatalogCommandService;
import com.sentinel.application.CatalogQueryService;
import com.sentinel.application.CatalogQueryService.CaseItem;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/cases")
class CaseController {

	private final CatalogQueryService catalog;

	private final CatalogCommandService commands;

	CaseController(CatalogQueryService catalog, CatalogCommandService commands) {
		this.catalog = catalog;
		this.commands = commands;
	}

	@GetMapping
	List<CaseItem> list(@RequestParam(required = false) Long suiteId) {
		return catalog.listCases(suiteId);
	}

	@GetMapping("/{caseId}")
	CaseItem get(@PathVariable long caseId) {
		return catalog.getCase(caseId);
	}

	/**
	 * 삭제(보관). 하위 항목 연쇄 보관, 실행 이력은 유지
	 */
	@DeleteMapping("/{caseId}")
	ResponseEntity<Void> delete(@PathVariable long caseId) {
		commands.archiveCase(caseId);
		return ResponseEntity.noContent().build();
	}

}
