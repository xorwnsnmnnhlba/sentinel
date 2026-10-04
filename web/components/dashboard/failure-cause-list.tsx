import Link from "next/link";

import type { FailureCauseGroup, FailureCauseSummary } from "@/lib/api/types";
import { formatCount } from "@/lib/dashboard";
import { CAUSE_LABELS, failingRunsHref, formatShare, GROUP_LABELS, moreCases } from "@/lib/failure-cause";
import { cn } from "@/lib/utils";

/** 막대 색은 묶음(먼저 볼 곳)별. 색만으로 구분하지 않도록 묶음 이름을 글자로 함께 표시 */
const barClass: Record<FailureCauseGroup, string> = {
  NETWORK: "bg-(--chart-series-2)",
  API: "bg-(--delta-bad)",
  SETUP: "bg-(--chart-series-1)",
  OTHER: "bg-muted-foreground",
};

/**
 * 대시보드의 실패 원인 (결과가 많은 원인 순). 원인 · 묶음 · 전체 실패 대비 막대와 건수 · 결과가 가장 많은 케이스.
 * 케이스를 누르면 그 스위트·환경의 기간 안 실패 실행 목록으로 이동
 * @param total 기간 안 전체 실패·오류 결과 수
 * @param from 기간 시작일 (YYYY-MM-DD, KST)
 */
export function FailureCauseList({
  causes,
  total,
  from,
  to,
}: {
  causes: FailureCauseSummary[];
  total: number;
  from: string;
  to: string;
}) {
  return (
    <ul className="divide-y" aria-label="실패 원인">
      {causes.map((summary) => {
        const top = summary.cases[0];
        const share = formatShare(summary.results, total);
        return (
          <li
            key={summary.cause}
            className="grid gap-x-6 gap-y-2 px-4 py-3 text-sm md:grid-cols-[11rem_minmax(0,1fr)_16rem] md:items-center"
          >
            <div>
              <div className="font-medium">{CAUSE_LABELS[summary.cause]}</div>
              <div className="font-mono text-xs text-muted-foreground">{GROUP_LABELS[summary.group]}</div>
            </div>
            <div className="flex items-center gap-3">
              <div aria-hidden className="h-2 flex-1 bg-muted">
                <div className={cn("h-full", barClass[summary.group])} style={{ width: share }} />
              </div>
              <span className="w-24 shrink-0 text-right font-mono text-xs tabular-nums">
                {formatCount(summary.results)}건 · {share}
              </span>
            </div>
            <div className="min-w-0 truncate text-xs text-muted-foreground">
              {top && (
                <>
                  <Link href={failingRunsHref(top, from, to)} className="font-medium text-foreground hover:underline">
                    {top.testCase.name}
                  </Link>{" "}
                  ({top.environment.name}){moreCases(summary.caseCount, 1)}
                </>
              )}
            </div>
          </li>
        );
      })}
    </ul>
  );
}
