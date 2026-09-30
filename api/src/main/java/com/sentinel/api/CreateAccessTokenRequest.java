package com.sentinel.api;

import jakarta.validation.constraints.NotNull;

/**
 * @param name 용도 (1~100자)
 * @param validityDays 유효 기간(일). 30·90·180·365 중 하나
 */
record CreateAccessTokenRequest(String name, @NotNull Integer validityDays) {
}
