/** 상세 화면 구획 공통 요소 */

/**
 * 구획 머리. 추가 동작은 제목과 같은 줄 오른쪽 (DESIGN.md "버튼 배치")
 */
export function SectionHeader({
  title,
  count,
  description,
  action,
}: {
  title: string;
  /** 항목 수. 제목 옆에 옅게 표시 */
  count?: number;
  description: string;
  action?: React.ReactNode;
}) {
  return (
    <div className="flex flex-wrap items-start justify-between gap-x-4 gap-y-2">
      <div className="min-w-0">
        <h2 className="text-base font-semibold">
          {title}
          {count !== undefined && <span className="ml-1.5 font-normal text-muted-foreground">{count}</span>}
        </h2>
        <p className="text-sm text-muted-foreground">{description}</p>
      </div>
      {action && <div className="flex items-center gap-1.5">{action}</div>}
    </div>
  );
}

/** 표 테두리 */
export function TableFrame({ children }: { children: React.ReactNode }) {
  return <div className="overflow-hidden rounded-xl border">{children}</div>;
}

export function Empty({ message }: { message: string }) {
  return (
    <p className="rounded-xl border border-dashed bg-muted/40 p-8 text-center text-sm text-muted-foreground">
      {message}
    </p>
  );
}
