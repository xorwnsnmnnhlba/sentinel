package com.sentinel.api.response;

import java.util.List;

import com.sentinel.application.CatalogQueryService.CaseItem;

/**
 * 케이스 가져오기 결과 ({@code POST /api/case-imports})
 *
 * @param cases 등록한 케이스
 */
public record CaseImportResponse(long suiteId, List<CaseItem> cases) {
}
