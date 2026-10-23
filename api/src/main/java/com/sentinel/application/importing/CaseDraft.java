package com.sentinel.application.importing;

import java.util.List;

import com.sentinel.application.CatalogQueryService.Request;
import com.sentinel.runner.assertion.Assertion;

/**
 * 가져오기 후보 케이스
 *
 * @param source 원본 위치. 예: {@code GET /pets/{petId}}
 * @param warnings 변환하면서 생략·대체한 내용
 */
record CaseDraft(String source, String name, Request request, List<Assertion> assertions,
		List<ImportWarning> warnings) {

	CaseDraft withName(String name) {
		return new CaseDraft(source, name, request, assertions, warnings);
	}

}
