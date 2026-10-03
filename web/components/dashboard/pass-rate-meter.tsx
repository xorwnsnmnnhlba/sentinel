import { formatPercent } from "@/lib/dashboard";

/**
 * 통과율 막대. 채움과 빈 부분이 같은 파랑 계열이라 전체 대비 비율로 읽힘. 값은 옆에 숫자로도 표시
 */
export function PassRateMeter({ rate, label }: { rate: number | null; label: string }) {
  if (rate === null) {
    return <span className="text-muted-foreground">–</span>;
  }
  return (
    <div className="flex items-center justify-end gap-3">
      <div
        role="meter"
        aria-label={label}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={Math.floor(rate * 1000) / 10}
        className="h-1.5 w-24 overflow-hidden bg-(--meter-track)"
      >
        <div className="h-full bg-(--chart-series-1)" style={{ width: `${rate * 100}%` }} />
      </div>
      <span className="w-14 text-right font-mono text-xs tabular-nums">{formatPercent(rate)}</span>
    </div>
  );
}
