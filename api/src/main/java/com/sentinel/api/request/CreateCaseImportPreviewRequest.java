package com.sentinel.api.request;

import com.sentinel.application.importing.CaseImportService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * @param content 문서 원문 (JSON 또는 YAML)
 */
public record CreateCaseImportPreviewRequest(@NotNull Long suiteId, @NotNull CaseImportService.Format format,
		@NotBlank String content) {
}
