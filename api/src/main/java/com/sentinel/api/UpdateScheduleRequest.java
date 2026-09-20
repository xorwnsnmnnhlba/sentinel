package com.sentinel.api;

/**
 * 스케줄 부분 수정. null 필드는 변경하지 않음.
 *
 * @param cron 5자리 표준 cron. 활성 상태면 다음 실행 시각 재계산
 * @param environmentId 실행 환경. 스위트와 같은 프로젝트의 환경만 허용
 */
record UpdateScheduleRequest(Boolean enabled, String cron, Long environmentId) {
}
