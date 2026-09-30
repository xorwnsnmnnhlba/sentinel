import type { UnstableCase } from "@/lib/api/types";

/** 불안정 케이스 표시 문구 (api/README.md "불안정 케이스") */

/** 불안정한 이유. 해당하는 것만. 예: "실패 구간 3회 · 재시도 2회" */
export function unstableReason(item: UnstableCase): string {
  const reasons = [
    item.failureEpisodes >= 2 ? `실패 구간 ${item.failureEpisodes}회` : null,
    item.retriedRuns > 0 ? `재시도 ${item.retriedRuns}회` : null,
  ].filter((reason) => reason !== null);
  return reasons.join(" · ");
}

/** 케이스별로 묶음 (환경마다 따로 판단하므로 한 케이스에 여러 건) */
export function unstableByCase(cases: UnstableCase[]): Map<number, UnstableCase[]> {
  const grouped = new Map<number, UnstableCase[]>();
  for (const item of cases) {
    grouped.set(item.testCase.id, [...(grouped.get(item.testCase.id) ?? []), item]);
  }
  return grouped;
}

/** 케이스 1건의 환경별 이유. 예: "dev: 실패 구간 2회, staging: 재시도 3회" */
export function unstableDetail(items: UnstableCase[]): string {
  return items.map((item) => `${item.environment.name}: ${unstableReason(item)}`).join(", ");
}
