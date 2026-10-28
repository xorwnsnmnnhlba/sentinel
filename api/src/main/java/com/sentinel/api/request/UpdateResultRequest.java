package com.sentinel.api.request;

import jakarta.validation.constraints.NotNull;

/**
 * 결과 변경. 승인 대기 응답 구조를 기준선에 반영하는 {@code schemaPending: false}만 지원
 */
public record UpdateResultRequest(@NotNull Boolean schemaPending) {
}
