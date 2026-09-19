import Link from "next/link";

import { RunCountBadges, StatusBadge, TriggerBadge } from "@/components/run-badges";
import { Button } from "@/components/ui/button";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { apiGet } from "@/lib/api/server";
import type { Page, RunSummary, SuiteItem } from "@/lib/api/types";
import { formatDateTime, formatRunDuration } from "@/lib/format";
import { cn } from "@/lib/utils";

const PAGE_SIZE = 20;

export const metadata = { title: "실행 이력 · Sentinel" };

export default async function RunsPage(props: PageProps<"/runs">) {
  const searchParams = await props.searchParams;
  const page = Math.max(Number(single(searchParams.page)) || 0, 0);
  const suiteId = single(searchParams.suiteId);

  const query = new URLSearchParams({ page: String(page), size: String(PAGE_SIZE) });
  if (suiteId) {
    query.set("suiteId", suiteId);
  }
  const [runs, suites] = await Promise.all([
    apiGet<Page<RunSummary>>(`/api/runs?${query}`),
    apiGet<SuiteItem[]>("/api/suites"),
  ]);

  return (
    <div className="space-y-6">
      <div className="space-y-1">
        <h1 className="text-2xl font-semibold tracking-tight">실행 이력</h1>
        <p className="text-sm text-muted-foreground">최신순. 스케줄·수동 실행 전체 {runs.totalElements}건</p>
      </div>

      <nav className="flex flex-wrap gap-2" aria-label="스위트 필터">
        <FilterLink href="/runs" active={!suiteId} label="전체" />
        {suites.map((suite) => (
          <FilterLink
            key={suite.id}
            href={`/runs?suiteId=${suite.id}`}
            active={suiteId === String(suite.id)}
            label={suite.name}
          />
        ))}
      </nav>

      {runs.content.length === 0 ? (
        <p className="rounded-lg border border-dashed p-10 text-center text-sm text-muted-foreground">
          실행 이력이 없습니다
        </p>
      ) : (
        <div className="rounded-lg border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead className="w-16">#</TableHead>
                <TableHead>시작 시각 (KST)</TableHead>
                <TableHead>스위트</TableHead>
                <TableHead>환경</TableHead>
                <TableHead>주체</TableHead>
                <TableHead>상태</TableHead>
                <TableHead>결과</TableHead>
                <TableHead className="text-right">소요</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {runs.content.map((run) => (
                <TableRow key={run.id} className="relative">
                  <TableCell className="text-muted-foreground">
                    {/* 행 전체를 클릭 영역으로 사용 */}
                    <Link href={`/runs/${run.id}`} className="after:absolute after:inset-0">
                      {run.id}
                    </Link>
                  </TableCell>
                  <TableCell className="font-mono text-xs">{formatDateTime(run.startedAt)}</TableCell>
                  <TableCell>
                    <div className="font-medium">{run.suite.name}</div>
                    <div className="text-xs text-muted-foreground">{run.project.name}</div>
                  </TableCell>
                  <TableCell>{run.environment.name}</TableCell>
                  <TableCell>
                    <TriggerBadge trigger={run.trigger} />
                  </TableCell>
                  <TableCell>
                    <StatusBadge status={run.status} />
                  </TableCell>
                  <TableCell>
                    <RunCountBadges counts={run.summary} />
                  </TableCell>
                  <TableCell className="text-right text-muted-foreground">
                    {formatRunDuration(run.startedAt, run.finishedAt)}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}

      {runs.totalPages > 1 && (
        <div className="flex items-center justify-between text-sm">
          <span className="text-muted-foreground">
            {runs.page + 1} / {runs.totalPages} 페이지
          </span>
          <div className="flex gap-2">
            <PageLink page={runs.page - 1} suiteId={suiteId} disabled={runs.page === 0} label="이전" />
            <PageLink page={runs.page + 1} suiteId={suiteId} disabled={runs.page + 1 >= runs.totalPages} label="다음" />
          </div>
        </div>
      )}
    </div>
  );
}

function single(value: string | string[] | undefined): string | undefined {
  return Array.isArray(value) ? value[0] : value;
}

function FilterLink({ href, active, label }: { href: string; active: boolean; label: string }) {
  return (
    <Button asChild size="sm" variant={active ? "default" : "outline"} className={cn(!active && "text-muted-foreground")}>
      <Link href={href}>{label}</Link>
    </Button>
  );
}

function PageLink({ page, suiteId, disabled, label }: { page: number; suiteId?: string; disabled: boolean; label: string }) {
  if (disabled) {
    return (
      <Button size="sm" variant="outline" disabled>
        {label}
      </Button>
    );
  }
  const query = new URLSearchParams({ page: String(page) });
  if (suiteId) {
    query.set("suiteId", suiteId);
  }
  return (
    <Button asChild size="sm" variant="outline">
      <Link href={`/runs?${query}`}>{label}</Link>
    </Button>
  );
}
