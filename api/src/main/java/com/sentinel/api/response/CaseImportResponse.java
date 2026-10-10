package com.sentinel.api.response;

import java.util.List;

import com.sentinel.application.CatalogQueryService.CaseItem;
import com.sentinel.application.IdName;

/**
 * 케이스 가져오기 결과 ({@code POST /api/case-imports})
 *
 * @param suite 가져온 스위트
 * @param cases 등록한 케이스
 */
public record CaseImportResponse(IdName suite, List<CaseItem> cases) {
}
