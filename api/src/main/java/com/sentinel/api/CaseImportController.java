package com.sentinel.api;

import java.util.List;

import com.sentinel.api.request.CreateCaseImportPreviewRequest;
import com.sentinel.api.request.CreateCaseImportRequest;
import com.sentinel.api.response.CaseImportResponse;
import com.sentinel.application.CatalogQueryService.CaseItem;
import com.sentinel.application.importing.CaseImportService;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "케이스 가져오기")
@RequiredArgsConstructor
class CaseImportController {

	private final CaseImportService imports;

	private final CurrentUser currentUser;

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
		List<CaseItem> imported = imports.importCases(request.suiteId(), request.cases(), currentUser.get().id());
		// 가져오기는 1건 이상이며 모두 같은 스위트
		return new CaseImportResponse(imported.getFirst().suite(), imported);
	}
}
