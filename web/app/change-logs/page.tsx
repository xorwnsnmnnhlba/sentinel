import Link from "next/link";

import { Panel, PanelEmpty, PanelFooter } from "@/components/catalog/section";
import { PageHeader } from "@/components/page-header";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { apiGet, isApiError } from "@/lib/api/server";
import type { ChangeAction, ChangeLogItem, ChangeTargetType, Page, ProjectItem, SuiteItem } from "@/lib/api/types";
import { actionLabel, fieldLabel, isTargetType, TARGET_LABELS, TARGET_TYPES, targetHref } from "@/lib/change-log";
import { formatDateTime } from "@/lib/format";

const PAGE_SIZE = 20;

export const metadata = { title: "변경 이력 · Sentinel" };

type Filters = { projectId?: string; suiteId?: string; targetType?: ChangeTargetType; targetId?: string };

const actionStyle: Record<ChangeAction, string> = {
  CREATED: "bg-emerald-500/10 text-emerald-800 dark:text-emerald-400",
  UPDATED: "bg-sky-500/10 text-sky-800 dark:text-sky-400",
  DELETED: "bg-destructive/10 text-destructive",
};

/**
 * 설정 변경 이력. 최신순, 프로젝트·대상 종류로 좁히거나 항목 화면의 "변경 이력"에서 항목 1건만 표시
 * 스위트의 "변경 이력"은 스위트와 소속 케이스·스케줄을 함께 표시
 * 값은 남기지 않으므로 바뀐 항목 이름만 표시
 */
export default async function ChangeLogsPage(props: PageProps<"/change-logs">) {
  const searchParams = await props.searchParams;
  const page = Math.max(Number(single(searchParams.page)) || 0, 0);
  const targetType = single(searchParams.targetType);
  const filters: Filters = {
    projectId: numeric(single(searchParams.projectId)),
    suiteId: numeric(single(searchParams.suiteId)),
    targetType: isTargetType(targetType) ? targetType : undefined,
    targetId: isTargetType(targetType) ? numeric(single(searchParams.targetId)) : undefined,
  };
  const scoped = filters.targetType !== undefined && filters.targetId !== undefined;
  const suiteScoped = !scoped && filters.suiteId !== undefined;
  const hasFilter = Object.values(filters).some((value) => value);

  const query = new URLSearchParams({ page: String(page), size: String(PAGE_SIZE) });
  appendFilters(query, filters);
  const [logs, projects, suite] = await Promise.all([
    apiGet<Page<ChangeLogItem>>(`/api/change-logs?${query}`),
    apiGet<ProjectItem[]>("/api/projects"),
    suiteScoped ? suiteOrNull(filters.suiteId!) : null,
  ]);
  const projectNames = new Map(projects.map((project) => [project.id, project.name]));

  return (
    <div className="space-y-4">
      <PageHeader title="변경 이력" />

      {scoped || suiteScoped ? (
        <div className="flex flex-wrap items-center justify-between gap-2 rounded-lg border bg-card p-4 text-sm">
          {scoped ? (
            <p>
              {TARGET_LABELS[filters.targetType!]}{" "}
              <span className="font-medium">{logs.content[0]?.targetName ?? `#${filters.targetId}`}</span>의 변경 이력만
              표시 중
            </p>
          ) : (
            <p>
              스위트{" "}
              <span className="font-medium">
                {suite?.name ??
                  logs.content.find((log) => log.targetType === "SUITE")?.targetName ??
                  `#${filters.suiteId}`}
              </span>{" "}
              및 소속 케이스·스케줄의 변경 이력만 표시 중
            </p>
          )}
          <Button asChild variant="outline" size="sm">
            <Link href="/change-logs">전체 보기</Link>
          </Button>
        </div>
      ) : (
        <form
          method="get"
          action="/change-logs"
          aria-label="변경 이력 필터"
          className="flex flex-wrap items-end gap-3 rounded-lg border bg-card p-4"
        >
          <div className="space-y-1.5">
            <Label htmlFor="filter-project">프로젝트</Label>
            <NativeSelect id="filter-project" name="projectId" defaultValue={filters.projectId ?? ""} className="w-40">
              <NativeSelectOption value="">전체</NativeSelectOption>
              {projects.map((project) => (
                <NativeSelectOption key={project.id} value={project.id}>
                  {project.name}
                </NativeSelectOption>
              ))}
            </NativeSelect>
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="filter-target-type">대상</Label>
            <NativeSelect
              id="filter-target-type"
              name="targetType"
              defaultValue={filters.targetType ?? ""}
              className="w-32"
            >
              <NativeSelectOption value="">전체</NativeSelectOption>
              {TARGET_TYPES.map((type) => (
                <NativeSelectOption key={type} value={type}>
                  {TARGET_LABELS[type]}
                </NativeSelectOption>
              ))}
            </NativeSelect>
          </div>
          <div className="flex gap-2">
            <Button type="submit">조회</Button>
            {hasFilter && (
              <Button asChild variant="outline">
                <Link href="/change-logs">초기화</Link>
              </Button>
            )}
          </div>
        </form>
      )}

      <Panel
        title="기록"
        meta={
          <>
            {hasFilter ? "조건에 맞는 기록" : "전체"} {logs.totalElements}건 · 최신순 · 값은 남기지 않음
          </>
        }
      >
        {logs.content.length === 0 ? (
          <PanelEmpty message={hasFilter ? "조건에 맞는 변경 이력이 없습니다" : "변경 이력이 없습니다"} />
        ) : (
          <Table aria-label="변경 이력">
            <TableHeader>
              <TableRow>
                <TableHead>시각 (KST)</TableHead>
                <TableHead>변경한 사람</TableHead>
                <TableHead>대상</TableHead>
                <TableHead>동작</TableHead>
                <TableHead>바뀐 항목</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {logs.content.map((log) => {
                const href = targetHref(log);
                const projectName = log.projectId === null ? undefined : projectNames.get(log.projectId);
                return (
                  <TableRow key={log.id}>
                    <TableCell className="font-mono text-xs">{formatDateTime(log.occurredAt)}</TableCell>
                    <TableCell className="text-sm">{log.actor?.name ?? "알 수 없음"}</TableCell>
                    <TableCell className="whitespace-normal">
                      <div className="flex flex-wrap items-center gap-2">
                        <Badge variant="outline">{TARGET_LABELS[log.targetType]}</Badge>
                        {href ? (
                          <Link href={href} className="font-medium hover:underline">
                            {log.targetName}
                          </Link>
                        ) : (
                          <span className="font-medium">{log.targetName}</span>
                        )}
                      </div>
                      {projectName && log.targetType !== "PROJECT" && (
                        <div className="text-xs text-muted-foreground">{projectName}</div>
                      )}
                    </TableCell>
                    <TableCell>
                      <Badge className={actionStyle[log.action]}>{actionLabel(log)}</Badge>
                    </TableCell>
                    <TableCell className="text-sm whitespace-normal text-muted-foreground">
                      {log.fields.length > 0 ? log.fields.map(fieldLabel).join(", ") : "—"}
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        )}
        {logs.totalPages > 1 && (
          <PanelFooter>
            <span className="font-mono text-xs text-muted-foreground">
              {logs.page + 1} / {logs.totalPages} 페이지
            </span>
            <div className="flex gap-2">
              <PageLink page={logs.page - 1} filters={filters} disabled={logs.page === 0} label="이전" />
              <PageLink
                page={logs.page + 1}
                filters={filters}
                disabled={logs.page + 1 >= logs.totalPages}
                label="다음"
              />
            </div>
          </PanelFooter>
        )}
      </Panel>
    </div>
  );
}

/** 스위트 이름 표시용. 삭제(보관)된 스위트는 조회되지 않으므로 null (이력의 이름으로 대신 표시) */
async function suiteOrNull(suiteId: string): Promise<SuiteItem | null> {
  try {
    return await apiGet<SuiteItem>(`/api/suites/${suiteId}`);
  } catch (error) {
    if (isApiError(error) && error.status === 404) {
      return null;
    }
    throw error;
  }
}

function single(value: string | string[] | undefined): string | undefined {
  return Array.isArray(value) ? value[0] : value;
}

/** 양의 정수만 조건으로 사용. 그 외 값은 무시 */
function numeric(value: string | undefined): string | undefined {
  return value !== undefined && /^[1-9]\d*$/.test(value) ? value : undefined;
}

function appendFilters(query: URLSearchParams, filters: Filters) {
  Object.entries(filters).forEach(([name, value]) => {
    if (value) {
      query.set(name, value);
    }
  });
}

/** 페이지를 넘겨도 조건이 풀리지 않도록 이어붙임 */
function PageLink({
  page,
  filters,
  disabled,
  label,
}: {
  page: number;
  filters: Filters;
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
      <Link href={`/change-logs?${query}`}>{label}</Link>
    </Button>
  );
}
