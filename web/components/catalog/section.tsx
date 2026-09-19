/** 상세 화면 구획 공통 요소 */

export function SectionHeader({ title, description, action }: { title: string; description: string; action?: React.ReactNode }) {
  return (
    <div className="flex items-end justify-between gap-4">
      <div>
        <h2 className="text-lg font-semibold">{title}</h2>
        <p className="text-sm text-muted-foreground">{description}</p>
      </div>
      {action}
    </div>
  );
}

export function RowActions({ children }: { children: React.ReactNode }) {
  return <div className="flex justify-end gap-1">{children}</div>;
}

export function Empty({ message }: { message: string }) {
  return <p className="rounded-lg border border-dashed p-8 text-center text-sm text-muted-foreground">{message}</p>;
}
