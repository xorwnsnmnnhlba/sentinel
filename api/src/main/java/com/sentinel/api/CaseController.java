package com.sentinel.api;

import java.net.URI;
import java.util.List;

import com.sentinel.api.request.CreateCaseRequest;
import com.sentinel.api.request.UpdateCaseRequest;
import com.sentinel.application.CatalogCommandService;
import com.sentinel.application.CatalogQueryService;
import com.sentinel.application.CatalogQueryService.CaseItem;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "케이스")
@RequestMapping("/api/cases")
@RequiredArgsConstructor
class CaseController {

	private final CatalogQueryService catalog;

	private final CatalogCommandService commands;

	private final CurrentUser currentUser;

	/**
	 * 목록. q 지정 시 이름·경로 부분 일치 검색 (대소문자 무시, 최대 100건), suiteId와 함께 쓰면 그 스위트 안에서
	 */
	@GetMapping
	List<CaseItem> list(@RequestParam(required = false) Long suiteId, @RequestParam(required = false) String q) {
		return catalog.listCases(suiteId, q);
	}

	/** 단건. 요청 정의·판정 규칙·값 추출 포함 */
	@GetMapping("/{caseId}")
	CaseItem get(@PathVariable long caseId) {
		return catalog.getCase(caseId);
	}

	/**
	 * 등록. 201 + Location, 같은 부모 아래 사용 중인 이름이면 409
	 */
	@ResponseStatus(HttpStatus.CREATED)
	@PostMapping
	ResponseEntity<CaseItem> create(@Valid @RequestBody CreateCaseRequest request) {
		CaseItem created = commands.createCase(request.suiteId(), request.name(), request.request(), request.assertions(),
				request.extractors(), request.afterCaseId(), currentUser.get().id());
		return ResponseEntity.created(URI.create("/api/cases/" + created.id())).body(created);
	}

	/**
	 * 부분 수정. 생략한 필드는 유지
	 */
	@PatchMapping("/{caseId}")
	CaseItem update(@PathVariable long caseId, @RequestBody UpdateCaseRequest request) {
		return commands.updateCase(caseId, request.name(), request.request(), request.assertions(),
				request.extractors(), currentUser.get().id());
	}

	/**
	 * 삭제(보관). 하위 항목 연쇄 보관, 실행 이력은 유지
	 */
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@DeleteMapping("/{caseId}")
	ResponseEntity<Void> delete(@PathVariable long caseId) {
		commands.archiveCase(caseId, currentUser.get().id());
		return ResponseEntity.noContent().build();
	}

}
