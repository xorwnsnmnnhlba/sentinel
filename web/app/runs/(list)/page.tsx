import Link from "next/link";

import { AutoRefresh } from "@/components/auto-refresh";
import { PageHeader } from "@/components/page-header";
import { RunCountBadges, StatusBadge, TriggerBadge } from "@/components/run-badges";
import { RunFilters, type RunFilterValues } from "@/components/runs/run-filters";
import { Button } from "@/components/ui/button";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { apiGet } from "@/lib/api/server";
import type { EnvironmentItem, Page, ProjectItem, RunSummary, SuiteItem } from "@/lib/api/types";
import { formatDateTime, formatProgress, formatRunDuration } from "@/lib/format";

const PAGE_SIZE = 20;

/** 진행 중인 실행이 보이면 이 주기로 목록을 다시 받아 상태·결과 갱신 */
const REFRESH_MS = 3000;

export const metadata = { title: "실행 이력 · Sentinel" };

export default async function RunsPage(props: PageProps<"/runs">) {
  const searchParams = await props.searchParams;
  const page = Math.max(Number(single(searchParams.page)) || 0, 0);
  const filters: RunFilterValues = {
    projectId: single(searchParams.projectId),
    suiteId: single(searchParams.suiteId),
    environmentId: single(searchParams.environmentId),
    status: single(searchParams.status),
    from: single(searchParams.from),
    to: single(searchParams.to),
    failing: single(searchParams.failing) === "true" ? "true" : undefined,
  };

  const hasFilter = Object.values(filters).some((value) => value);

  const query = new URLSearchParams({ page: String(page), size: String(PAGE_SIZE) });
  appendFilters(query, filters);
  const [runs, projects, suites, environments] = await Promise.all([
    apiGet<Page<RunSummary>>(`/api/runs?${query}`),
    apiGet<ProjectItem[]>("/api/projects"),
    apiGet<SuiteItem[]>("/api/suites"),
    apiGet<EnvironmentItem[]>("/api/environments"),
  ]);

  const running = runs.content.some((run) => run.status === "RUNNING");

  return (
    <div className="space-y-6">
      <PageHeader
        title="실행 이력"
        description={`최신순. ${hasFilter ? "조건에 맞는 실행" : "전체"} ${runs.totalElements}건${
          running ? ". 진행 중인 실행이 있어 3초마다 새로 고침" : ""
        }`}
      />
      {running && <AutoRefresh intervalMs={REFRESH_MS} />}

      <RunFilters projects={projects} suites={suites} environments={environments} values={filters} />

      {runs.content.length === 0 ? (
        <p className="rounded-xl border border-dashed p-10 text-center text-sm text-muted-foreground">
          {hasFilter ? "조건에 맞는 실행 이력이 없습니다" : "실행 이력이 없습니다"}
        </p>
      ) : (
        <div className="rounded-xl border">
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
                    <div className="text-xs text-muted-foreground">
                      {run.project.name}
                      {run.testCase && <> · 케이스 단건: {run.testCase.name}</>}
                      {run.throughCase && <> · {run.throughCase.name}까지</>}
                    </div>
                  </TableCell>
                  <TableCell>{run.environment.name}</TableCell>
                  <TableCell>
                    <TriggerBadge trigger={run.trigger} />
                    {run.triggeredBy && <div className="text-xs text-muted-foreground">{run.triggeredBy.name}</div>}
                  </TableCell>
                  <TableCell>
                    <StatusBadge status={run.status} />
                    {run.status === "RUNNING" && (
                      <div className="text-xs text-muted-foreground">
                        {formatProgress(run.summary.total, run.plannedCount)} 완료
                      </div>
                    )}
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
            <PageLink page={runs.page - 1} filters={filters} disabled={runs.page === 0} label="이전" />
            <PageLink page={runs.page + 1} filters={filters} disabled={runs.page + 1 >= runs.totalPages} label="다음" />
          </div>
        </div>
      )}
    </div>
  );
}

function single(value: string | string[] | undefined): string | undefined {
  return Array.isArray(value) ? value[0] : value;
}

/** 비어 있는 조건은 빼고 넘겨 주소와 API 호출을 간결하게 유지 */
function appendFilters(query: URLSearchParams, filters: RunFilterValues) {
  Object.entries(filters).forEach(([name, value]) => {
    if (value) {
      query.set(name, value);
    }
  });
}

/** 페이지를 넘겨도 필터가 풀리지 않도록 조건을 그대로 이어붙임 */
function PageLink({
  page,
  filters,
  disabled,
  label,
}: {
  page: number;
  filters: RunFilterValues;
  disabled: boolean;
  label: string;
}) {
  if (disabled) {
    return (
      <Button size="sm" variant="outline" disabled>
        {label}
      </Button>
    );
  }
  const query = new URLSearchParams({ page: String(page) });
  appendFilters(query, filters);
  return (
    <Button asChild size="sm" variant="outline">
      <Link href={`/runs?${query}`}>{label}</Link>
    </Button>
  );
}
