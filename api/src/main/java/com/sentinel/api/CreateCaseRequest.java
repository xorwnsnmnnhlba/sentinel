package com.sentinel.api;

import java.util.List;

import com.sentinel.application.CatalogQueryService.Request;
import com.sentinel.runner.assertion.Assertion;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * @param request 요청 정의. 조회 응답의 {@code request}와 같은 형태
 * @param assertions 판정 규칙. 생략 또는 빈 목록이면 기본 판정(status in [2xx])
 */
public record CreateCaseRequest(@NotNull Long suiteId, @NotBlank String name, @NotNull Request request,
		List<Assertion> assertions) {
}
