package com.sentinel.api.request;

import java.util.List;

import com.sentinel.application.CatalogQueryService.Request;
import com.sentinel.runner.assertion.Assertion;
import com.sentinel.runner.extract.Extractor;

/**
 * 케이스 부분 수정. null 필드는 변경하지 않음.
 *
 * @param request 지정 시 요청 정의 전체 교체
 * @param assertions 지정 시 전체 교체. 빈 목록이면 기본 판정으로 복귀
 * @param extractors 지정 시 전체 교체. 빈 목록이면 추출 없음
 * @param version 수정 화면을 열 때 읽은 {@code version}. 지정 시 그사이 다른 곳에서 고쳤으면 409 (생략하면 확인하지 않음)
 */
public record UpdateCaseRequest(String name, Request request, List<Assertion> assertions, List<Extractor> extractors,
		Long version) {
}
