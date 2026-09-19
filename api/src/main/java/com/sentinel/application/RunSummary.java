package com.sentinel.application;

import java.time.Instant;

import com.sentinel.domain.TestRun;

/**
 * 실행 이력 목록의 한 행.
 */
public record RunSummary(long id, IdName project, IdName suite, IdName environment, TestRun.Trigger trigger,
		TestRun.Status status, Instant startedAt, Instant finishedAt, TestRunDetail.Summary summary) {
}
