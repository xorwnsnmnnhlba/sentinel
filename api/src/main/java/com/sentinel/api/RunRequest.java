package com.sentinel.api;

import jakarta.validation.constraints.NotNull;

public record RunRequest(@NotNull Long environmentId) {
}
