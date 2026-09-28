package com.sentinel.application;

import java.time.Instant;

import com.sentinel.domain.TestRun;

/**
 * 실행 이력 목록의 한 행.
 *
 * @param triggeredBy 수동 실행을 시작한 사용자. 스케줄 실행·이전 기록은 null
 * @param testCase 케이스 단건 실행의 대상. 스위트 전체 실행은 null
 * @param throughCase 여기까지 실행의 마지막 케이스. 그 외 실행은 null
 * @param plannedCount 실행할 케이스 수. 진행 중이면 summary.total과 비교해 진행률 표시. 이전 실행은 null
 */
public record RunSummary(long id, IdName project, IdName suite, IdName environment, TestRun.Trigger trigger,
		TestRun.Status status, Instant startedAt, Instant finishedAt, TestRunDetail.Summary summary,
		IdName triggeredBy, IdName testCase, IdName throughCase, Integer plannedCount) {
}
