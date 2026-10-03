package com.sentinel.api.request;

import java.util.List;

import com.sentinel.application.importing.CaseImportService;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * @param cases 등록할 케이스. 미리보기 응답의 {@code cases} 항목과 같은 형태 ({@code name}, {@code request},
 * {@code assertions}만 사용)
 */
public record CreateCaseImportRequest(@NotNull Long suiteId, @NotEmpty List<CaseImportService.NewCase> cases) {
}
