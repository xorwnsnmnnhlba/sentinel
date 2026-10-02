import type { MonitoringGap } from "@/lib/api/types";

/** 모니터링 공백 표시 문구 (api/README.md "모니터링 공백") */

const MINUTE_MS = 60 * 1000;
const HOUR_MS = 60 * MINUTE_MS;

/** 공백 길이 (ms) */
export function gapDuration(gap: MonitoringGap): number {
  return Date.parse(gap.endedAt) - Date.parse(gap.startedAt);
}

/** 기간 안 공백 길이 합계 (ms). 대시보드 기간 밖으로 걸친 부분도 포함 */
export function totalGapDuration(gaps: MonitoringGap[]): number {
  return gaps.reduce((sum, gap) => sum + gapDuration(gap), 0);
}

/** 길이 문구. 알림과 같은 표기. 예: "48초", "30분", "2시간 30분", "26시간 0분" */
export function formatGapDuration(ms: number): string {
  if (ms < MINUTE_MS) {
    return `${Math.floor(ms / 1000)}초`;
  }
  if (ms < HOUR_MS) {
    return `${Math.floor(ms / MINUTE_MS)}분`;
  }
  return `${Math.floor(ms / HOUR_MS)}시간 ${Math.floor((ms % HOUR_MS) / MINUTE_MS)}분`;
}
