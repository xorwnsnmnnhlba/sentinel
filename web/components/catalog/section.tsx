/** 화면 구획 공통 요소 (DESIGN.md "패널") */

/**
 * 패널. 위쪽 머리 막대(제목·건수 | 보조 정보·동작)와 본문. 표는 본문에 바로 넣고, 여백이 필요한 내용은 {@link PanelBody}
 * 설명 문장은 두지 않음. 기간·기준처럼 값을 읽는 데 필요한 정보만 meta로 짧게
 */
export function Panel({
  title,
  count,
  meta,
  action,
  id,
  children,
}: {
  title: React.ReactNode;
  /** 항목 수. 제목 옆에 옅게 표시 */
  count?: number;
  /** 머리 막대 오른쪽의 짧은 보조 정보 (예: 기간, 비교 기준) */
  meta?: React.ReactNode;
  action?: React.ReactNode;
  id?: string;
  children: React.ReactNode;
}) {
  return (
    <section id={id} className="overflow-hidden rounded-lg border bg-card">
      <div className="flex min-h-11 flex-wrap items-center justify-between gap-x-4 gap-y-2 border-b px-4 py-2">
        <h2 className="text-sm font-semibold">
          {title}
          {count !== undefined && <span className="ml-2 font-mono font-normal text-muted-foreground">{count}</span>}
        </h2>
        {(meta || action) && (
          <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
            {meta && <span className="font-mono text-xs text-muted-foreground">{meta}</span>}
            {action}
          </div>
        )}
      </div>
      {children}
    </section>
  );
}

/** 패널 본문 여백. 표가 아닌 내용(폼, 설명, 목록)에 사용 */
export function PanelBody({ children }: { children: React.ReactNode }) {
  return <div className="p-4">{children}</div>;
}

/** 패널 안의 빈 상태 */
export function PanelEmpty({ message }: { message: React.ReactNode }) {
  return <p className="px-4 py-8 text-center text-sm text-muted-foreground">{message}</p>;
}

/** 패널 밖에 단독으로 놓는 표 테두리 */
export function TableFrame({ children }: { children: React.ReactNode }) {
  return <div className="overflow-hidden rounded-lg border bg-card">{children}</div>;
}

/** 패널 밖에 단독으로 놓는 빈 상태 */
export function Empty({ message }: { message: React.ReactNode }) {
  return <p className="rounded-lg border border-dashed p-8 text-center text-sm text-muted-foreground">{message}</p>;
}
