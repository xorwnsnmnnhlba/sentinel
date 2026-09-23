package com.sentinel.api;

import java.util.List;

import com.sentinel.application.CatalogQueryService.CaseItem;
import com.sentinel.application.importing.CaseImportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 외부 문서에서 케이스 가져오기. 미리보기로 후보를 확인한 뒤 고른 케이스만 가져오기
 */
@RestController
@RequiredArgsConstructor
class CaseImportController {

	private final CaseImportService imports;

	/**
	 * 문서를 케이스 후보로 변환. 저장하지 않음
	 */
	@PostMapping("/api/case-import-previews")
	CaseImportService.Preview preview(@Valid @RequestBody CreateCaseImportPreviewRequest request) {
		return imports.preview(request.suiteId(), request.format(), request.content());
	}

	/**
	 * 한 트랜잭션으로 등록. 한 건이라도 실패하면 전부 미등록 (이름 중복 409, 잘못된 케이스 400)
	 */
	@PostMapping("/api/case-imports")
	@ResponseStatus(HttpStatus.CREATED)
	CaseImportResponse create(@Valid @RequestBody CreateCaseImportRequest request) {
		return new CaseImportResponse(request.suiteId(), imports.importCases(request.suiteId(), request.cases()));
	}

	record CaseImportResponse(long suiteId, List<CaseItem> cases) {
	}

}
