package com.sentinel.api;

import jakarta.validation.constraints.NotNull;

public record RunTestCaseRequest(@NotNull Long environmentId) {
}
