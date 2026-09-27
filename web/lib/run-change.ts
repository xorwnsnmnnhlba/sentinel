import type { RunChange, RunResult } from "@/lib/api/types";

/** 직전 실행 대비 판정 변화 표시 (api/README.md "직전 실행 대비 변화") */

/** 요약·배지에 쓰는 변화. 통과 → 통과(UNCHANGED)는 표시하지 않음 */
export const SHOWN_CHANGES = ["NEWLY_FAILING", "RECOVERED", "STILL_FAILING", "NEW"] as const;

export type ShownChange = (typeof SHOWN_CHANGES)[number];

export const CHANGE_LABELS: Record<ShownChange, string> = {
  NEWLY_FAILING: "새로 실패",
  RECOVERED: "복구",
  STILL_FAILING: "계속 실패",
  NEW: "새 케이스",
};

export function isShownChange(change: RunChange | null): change is ShownChange {
  return change !== null && (SHOWN_CHANGES as readonly string[]).includes(change);
}

/** 변화별 케이스 수. 0건인 변화는 제외, 표시 순서(새로 실패 → 복구 → 계속 실패 → 새 케이스) */
export function countChanges(results: RunResult[]): { change: ShownChange; count: number }[] {
  return SHOWN_CHANGES.map((change) => ({
    change,
    count: results.filter((result) => result.change === change).length,
  })).filter(({ count }) => count > 0);
}
