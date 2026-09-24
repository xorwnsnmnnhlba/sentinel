import { ArrowDown, ArrowUp, Minus } from "lucide-react";

import type { Delta } from "@/lib/dashboard";
import { cn } from "@/lib/utils";

const toneClass = {
  good: "text-(--delta-good)",
  bad: "text-(--delta-bad)",
  neutral: "text-muted-foreground",
} as const;

const toneLabel = { good: "좋아짐", bad: "나빠짐", neutral: "" } as const;

/**
 * 숫자 하나를 보여주는 타일. 라벨 · 값 · 이전 기간 대비 변화
 * 변화의 색은 좋고 나쁨을 뜻하므로 화살표와 문구를 함께 표시 (색만으로 구분하지 않음)
 */
export function StatTile({
  label,
  value,
  delta,
  comparedTo,
}: {
  label: string;
  value: string;
  delta: Delta | null;
  /** 예: "이전 7일 대비" */
  comparedTo: string;
}) {
  const Icon = delta?.direction === "up" ? ArrowUp : delta?.direction === "down" ? ArrowDown : Minus;
  return (
    <div className="space-y-1 rounded-lg border p-4">
      <p className="text-sm text-muted-foreground">{label}</p>
      <p className="text-3xl font-semibold tracking-tight">{value}</p>
      {delta ? (
        <p className={cn("flex items-center gap-1 text-xs", toneClass[delta.tone])}>
          <Icon aria-hidden className="size-3.5" />
          <span>
            {delta.text}
            {toneLabel[delta.tone] && <span className="sr-only"> ({toneLabel[delta.tone]})</span>}
          </span>
          <span className="text-muted-foreground">{comparedTo}</span>
        </p>
      ) : (
        <p className="text-xs text-muted-foreground">비교할 이전 기간 결과 없음</p>
      )}
    </div>
  );
}
