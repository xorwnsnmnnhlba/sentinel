import Link from "next/link";

import { Badge } from "@/components/ui/badge";
import type { RunChange, RunDetail } from "@/lib/api/types";
import { formatDateTime } from "@/lib/format";
import { CHANGE_LABELS, countChanges, isShownChange, type ShownChange } from "@/lib/run-change";

const changeStyle: Record<ShownChange, string> = {
  NEWLY_FAILING: "border-destructive/40 text-destructive",
  RECOVERED: "border-emerald-500/40 text-emerald-700 dark:text-emerald-400",
  STILL_FAILING: "text-muted-foreground",
  NEW: "text-muted-foreground",
};

/** 케이스 행의 직전 실행 대비 변화. 그대로 통과했거나 비교하지 않으면 표시 없음 */
export function ChangeBadge({ change }: { change: RunChange | null }) {
  if (!isShownChange(change)) {
    return null;
  }
  return (
    <Badge variant="outline" className={changeStyle[change]}>
      {CHANGE_LABELS[change]}
    </Badge>
  );
}

/**
 * 결과 상단의 직전 실행 대비 요약. 스위트 전체 실행만 비교하므로 단건·여기까지·실패 다시 실행은 표시 없음
 * 직전 실행이 없으면 첫 실행이라는 안내
 */
export function ChangeSummary({ run }: { run: RunDetail }) {
  if (run.testCase || run.throughCase || run.rerunOf !== null) {
    return null;
  }
  if (!run.previousRun) {
    return (
      <p className="text-sm text-muted-foreground">같은 스위트·환경의 완료된 이전 실행이 없어 비교하지 않습니다</p>
    );
  }
  const counts = countChanges(run.results);
  return (
    <p className="flex flex-wrap items-center gap-x-2 gap-y-1 text-sm">
      <span className="text-muted-foreground">
        <Link href={`/runs/${run.previousRun.id}`} className="underline-offset-4 hover:underline">
          직전 실행 #{run.previousRun.id}
        </Link>{" "}
        ({formatDateTime(run.previousRun.startedAt)}) 대비
      </span>
      {counts.length === 0 ? (
        <span>판정 변화 없음</span>
      ) : (
        counts.map(({ change, count }) => (
          <Badge key={change} variant="outline" className={changeStyle[change]}>
            {CHANGE_LABELS[change]} {count}
          </Badge>
        ))
      )}
    </p>
  );
}
