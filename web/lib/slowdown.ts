import type { Slowdown } from "@/lib/api/types";

/** 지금 느린 케이스 표시 문구 (api/README.md "응답시간 악화 알림") */

/** 늘어난 비율. 예: 0.906 → "+91%" */
export function formatIncrease(increase: number): string {
  return `+${Math.round(increase * 100)}%`;
}

/** 감지 당시 p95 변화. 예: "320ms → 610ms" */
export function formatP95Change(slowdown: Slowdown): string {
  return `${slowdown.baselineP95}ms → ${slowdown.detectedP95}ms`;
}

/** 스위트 화면의 그 케이스·환경 응답시간 추세 */
export function responseTimesHref(slowdown: Slowdown): string {
  return `/suites/${slowdown.suite.id}?environmentId=${slowdown.environment.id}&caseId=${slowdown.testCase.id}#response-times`;
}

/** 케이스별로 묶음 (환경마다 따로 판단하므로 한 케이스에 여러 건) */
export function slowdownsByCase(slowdowns: Slowdown[]): Map<number, Slowdown[]> {
  const grouped = new Map<number, Slowdown[]>();
  for (const slowdown of slowdowns) {
    grouped.set(slowdown.testCase.id, [...(grouped.get(slowdown.testCase.id) ?? []), slowdown]);
  }
  return grouped;
}

/** 케이스 1건의 환경별 악화. 예: "dev: 320ms → 610ms (+91%), staging: 200ms → 400ms (+100%)" */
export function slowdownDetail(slowdowns: Slowdown[]): string {
  return slowdowns
    .map(
      (slowdown) => `${slowdown.environment.name}: ${formatP95Change(slowdown)} (${formatIncrease(slowdown.increase)})`,
    )
    .join(", ");
}
