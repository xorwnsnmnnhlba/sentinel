package com.sentinel.api;

import java.util.List;

import com.sentinel.application.CatalogQueryService.Request;
import com.sentinel.runner.assertion.Assertion;

/**
 * 케이스 부분 수정. null 필드는 변경하지 않음.
 *
 * @param request 지정 시 요청 정의 전체 교체
 * @param assertions 지정 시 전체 교체. 빈 목록이면 기본 판정으로 복귀
 */
record UpdateCaseRequest(String name, Request request, List<Assertion> assertions) {
}
