import type { MonitoringGap } from "@/lib/api/types";
import { formatPeriod } from "@/lib/maintenance";
import { formatGapDuration, gapDuration } from "@/lib/monitoring-gap";

/**
 * 대시보드의 모니터링 공백 (최근 시작 순, 최대 limit건). 기간은 KST, 대시보드 기간 밖으로 걸친 공백은 전체 기간 표시
 */
export function MonitoringGapList({ gaps, limit }: { gaps: MonitoringGap[]; limit: number }) {
  return (
    <div className="space-y-2">
      <ul className="divide-y rounded-xl border text-sm" aria-label="모니터링 공백">
        {gaps.slice(0, limit).map((gap) => (
          <li key={gap.id} className="flex flex-wrap items-center gap-x-3 gap-y-1 px-4 py-2.5">
            <span className="font-mono">{formatPeriod(gap.startedAt, gap.endedAt)}</span>
            <span className="font-medium">{formatGapDuration(gapDuration(gap))}</span>
            <span className="text-muted-foreground">제때 실행되지 않은 스케줄 {gap.missedSchedules}건</span>
          </li>
        ))}
      </ul>
      {gaps.length > limit && (
        <p className="text-xs text-muted-foreground">
          {gaps.length}건 중 최근 {limit}건
        </p>
      )}
    </div>
  );
}
