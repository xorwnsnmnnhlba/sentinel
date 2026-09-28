import { Badge } from "@/components/ui/badge";
import type { RunCounts, RunStatus, Trigger, Verdict } from "@/lib/api/types";

const verdictStyle: Record<Verdict, { label: string; className: string }> = {
  PASSED: { label: "통과", className: "bg-emerald-500/10 text-emerald-700 dark:text-emerald-400" },
  FAILED: { label: "실패", className: "bg-destructive/10 text-destructive" },
  ERROR: { label: "오류", className: "bg-amber-500/10 text-amber-700 dark:text-amber-400" },
};

export function VerdictBadge({ verdict }: { verdict: Verdict | null }) {
  if (!verdict) {
    return <Badge variant="outline">판정 없음</Badge>;
  }
  const style = verdictStyle[verdict];
  return (
    <Badge className={style.className}>
      <span aria-hidden className="size-1.5 rounded-full bg-current" />
      {style.label}
    </Badge>
  );
}

export function TriggerBadge({ trigger }: { trigger: Trigger }) {
  return <Badge variant="outline">{trigger === "SCHEDULED" ? "스케줄" : "수동"}</Badge>;
}

/** 케이스 하나만 실행한 기록. 스위트 전체 실행과 구분 */
export function SingleCaseBadge() {
  return <Badge variant="outline">케이스 단건</Badge>;
}

/** 스위트 처음부터 한 케이스까지만 실행한 기록. 스위트 전체 실행과 구분 */
export function ThroughCaseBadge() {
  return <Badge variant="outline">여기까지</Badge>;
}

const statusLabel: Record<RunStatus, string> = {
  RUNNING: "실행 중",
  COMPLETED: "완료",
  ABORTED: "중단",
  CANCELED: "중지",
};

export function StatusBadge({ status }: { status: RunStatus }) {
  return <Badge variant={status === "COMPLETED" ? "secondary" : "outline"}>{statusLabel[status]}</Badge>;
}

/** 0건인 항목은 회색으로 낮춰 실패·오류가 눈에 띄도록 함. 투명도로 흐리게 하면 글자 대비가 기준(4.5:1) 미달 */
export function RunCountBadges({ counts }: { counts: RunCounts }) {
  return (
    <div className="flex items-center gap-1.5">
      <CountBadge verdict="PASSED" count={counts.passed} />
      <CountBadge verdict="FAILED" count={counts.failed} />
      <CountBadge verdict="ERROR" count={counts.errors} />
      <span className="text-xs text-muted-foreground">/ {counts.total}</span>
    </div>
  );
}

function CountBadge({ verdict, count }: { verdict: Verdict; count: number }) {
  const style = verdictStyle[verdict];
  return (
    <Badge className={count === 0 ? "bg-muted text-muted-foreground" : style.className}>
      {style.label} {count}
    </Badge>
  );
}
