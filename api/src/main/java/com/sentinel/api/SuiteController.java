package com.sentinel.api;

import java.net.URI;
import java.util.List;

import com.sentinel.application.CatalogCommandService;
import com.sentinel.application.CatalogQueryService;
import com.sentinel.application.CatalogQueryService.SuiteItem;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/suites")
@RequiredArgsConstructor
class SuiteController {

	private final CatalogQueryService catalog;

	private final CatalogCommandService commands;

	@GetMapping
	List<SuiteItem> list(@RequestParam(required = false) Long projectId) {
		return catalog.listSuites(projectId);
	}

	@GetMapping("/{suiteId}")
	SuiteItem get(@PathVariable long suiteId) {
		return catalog.getSuite(suiteId);
	}

	/**
	 * 등록. 201 + Location, 같은 부모 아래 사용 중인 이름이면 409
	 */
	@PostMapping
	ResponseEntity<SuiteItem> create(@Valid @RequestBody CreateSuiteRequest request) {
		SuiteItem created = commands.createSuite(request.projectId(), request.name(), request.description());
		return ResponseEntity.created(URI.create("/api/suites/" + created.id())).body(created);
	}

	/**
	 * 부분 수정. 생략한 필드는 유지
	 */
	@PatchMapping("/{suiteId}")
	SuiteItem update(@PathVariable long suiteId, @RequestBody UpdateSuiteRequest request) {
		return commands.updateSuite(suiteId, request.name(), request.description(), request.caseOrder());
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
