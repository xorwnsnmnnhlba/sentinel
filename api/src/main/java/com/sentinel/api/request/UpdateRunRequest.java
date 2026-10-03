package com.sentinel.api.request;

import com.sentinel.domain.TestRun;
import jakarta.validation.constraints.NotNull;

/**
 * 실행 상태 변경. 진행 중인 실행을 중지하는 {@code CANCELED}만 지원
 */
public record UpdateRunRequest(@NotNull TestRun.Status status) {
}
