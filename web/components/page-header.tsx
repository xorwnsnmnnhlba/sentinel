import { ChevronLeft } from "lucide-react";
import Link from "next/link";

/**
 * 화면 머리 (DESIGN.md "버튼 배치"). 왼쪽: 상위 화면 링크·제목·보조 정보, 오른쪽: 보조 버튼 → 주 버튼 → "⋯" 메뉴 순
 * description은 설명 문장이 아니라 사용자가 입력한 설명이나 건수·기간 같은 짧은 정보만
 */
export function PageHeader({
  back,
  title,
  description,
  actions,
}: {
  back?: { href: string; label: string };
  title: React.ReactNode;
  description?: React.ReactNode;
  actions?: React.ReactNode;
}) {
  return (
    <div className="space-y-1">
      {back && (
        <Link
          href={back.href}
          className="-ml-1 inline-flex items-center gap-0.5 text-sm text-muted-foreground transition-colors hover:text-foreground"
        >
          <ChevronLeft aria-hidden className="size-4" />
          {back.label}
        </Link>
      )}
      <div className="flex flex-wrap items-start justify-between gap-x-6 gap-y-3">
        <div className="min-w-0 space-y-1">
          <h1 className="text-xl font-semibold tracking-[-0.3px] break-words">{title}</h1>
          {description && <div className="text-sm text-muted-foreground">{description}</div>}
        </div>
        {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
      </div>
    </div>
  );
}
