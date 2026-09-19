package com.sentinel.api;

import jakarta.validation.constraints.NotBlank;

record CreateProjectRequest(@NotBlank String name, String description) {
}
