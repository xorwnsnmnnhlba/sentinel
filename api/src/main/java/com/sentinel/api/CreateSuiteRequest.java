package com.sentinel.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateSuiteRequest(@NotNull Long projectId, @NotBlank String name, String description) {
}
