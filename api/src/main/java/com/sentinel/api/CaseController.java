package com.sentinel.api;

import java.net.URI;
import java.util.List;

import com.sentinel.application.CatalogCommandService;
import com.sentinel.application.CatalogQueryService;
import com.sentinel.application.CatalogQueryService.CaseItem;
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
@RequestMapping("/api/cases")
@RequiredArgsConstructor
class CaseController {

	private final CatalogQueryService catalog;

	private final CatalogCommandService commands;

	@GetMapping
	List<CaseItem> list(@RequestParam(required = false) Long suiteId) {
		return catalog.listCases(suiteId);
	}

	@GetMapping("/{caseId}")
	CaseItem get(@PathVariable long caseId) {
		return catalog.getCase(caseId);
	}

	/**
	 * 등록. 201 + Location, 같은 부모 아래 사용 중인 이름이면 409
	 */
	@PostMapping
	ResponseEntity<CaseItem> create(@Valid @RequestBody CreateCaseRequest request) {
		CaseItem created = commands.createCase(request.suiteId(), request.name(), request.request(), request.assertions());
		return ResponseEntity.created(URI.create("/api/cases/" + created.id())).body(created);
	}

	/**
	 * 부분 수정. 생략한 필드는 유지
	 */
	@PatchMapping("/{caseId}")
	CaseItem update(@PathVariable long caseId, @RequestBody UpdateCaseRequest request) {
		return commands.updateCase(caseId, request.name(), request.request(), request.assertions());
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
