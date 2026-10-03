package com.sentinel.api.request;

import java.util.List;

import com.sentinel.application.CatalogQueryService.Request;
import com.sentinel.runner.assertion.Assertion;
import com.sentinel.runner.extract.Extractor;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * @param request 요청 정의. 조회 응답의 {@code request}와 같은 형태
 * @param assertions 판정 규칙. 생략 또는 빈 목록이면 기본 판정(status in [2xx])
 * @param extractors 응답 값 추출 규칙. 생략 시 없음
 * @param afterCaseId 바로 앞에 둘 케이스 (같은 스위트). 생략 시 스위트의 마지막 순서
 */
public record CreateCaseRequest(@NotNull Long suiteId, @NotBlank String name, @NotNull Request request,
		List<Assertion> assertions, List<Extractor> extractors, Long afterCaseId) {
}
