import Link from "next/link";

import { AutoRefresh } from "@/components/auto-refresh";
import { PageHeader } from "@/components/page-header";
import { PassRateMeter } from "@/components/dashboard/pass-rate-meter";
import { Panel, PanelEmpty } from "@/components/catalog/section";
import { MonitoringGapList } from "@/components/dashboard/monitoring-gap-list";
import { SlowdownTable } from "@/components/dashboard/slowdown-table";
import { StatTile } from "@/components/dashboard/stat-tile";
import { UnstableCaseTable } from "@/components/dashboard/unstable-case-table";
import { RunCountBadges, StatusBadge, TriggerBadge } from "@/components/run-badges";
import { Button } from "@/components/ui/button";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { apiGet } from "@/lib/api/server";
import type {
  MaintenanceWindow,
  MonitoringGaps,
  Page,
  ResponseTimeChanges,
  RunSummary,
  Slowdown,
  SuiteSummaries,
  UnstableCases,
} from "@/lib/api/types";
import {
  byPassRate,
  DASHBOARD_PERIODS,
  type DashboardPeriod,
  delta,
  formatChange,
  formatCount,
  formatPercent,
  parseDashboardPeriod,
  previousRange,
  totals,
} from "@/lib/dashboard";
import { formatDateTime, formatProgress } from "@/lib/format";
import { formatEnd } from "@/lib/maintenance";
import { formatGapDuration, totalGapDuration } from "@/lib/monitoring-gap";
import { formatMs, kstDateRange } from "@/lib/response-times";
import { cn } from "@/lib/utils";

export const metadata = { title: "대시보드 · Sentinel" };

/** 불안정 케이스 표시 수 (실패 구간·재시도가 많은 순) */
const UNSTABLE_LIMIT = 10;

/** 지금 느린 케이스 표시 수 (늘어난 비율이 큰 순) */
const SLOWDOWN_LIMIT = 10;

/** 모니터링 공백 표시 수 (최근 시작 순) */
const GAP_LIMIT = 5;

/** 진행 중인 실행 표시 수와 새로 고침 주기 */
const RUNNING_LIMIT = 10;
const RUNNING_REFRESH_MS = 3000;

/**
 * 홈 대시보드. 기간(최근 7·30일, KST) 동안의 실행 현황과 이전 같은 길이 기간 대비 변화
 */
export default async function Dashboard(props: PageProps<"/">) {
  const searchParams = await props.searchParams;
  const days = parseDashboardPeriod(single(searchParams.days));
  const current = kstDateRange(days);
  const previous = previousRange(current.from, days);
  const range = (period: { from: string; to: string }) => `from=${period.from}&to=${period.to}`;

  const [summaries, previousSummaries, failingRuns, changes, running, windows, unstable, monitoringGaps, slowdowns] =
    await Promise.all([
      apiGet<SuiteSummaries>(`/api/suite-summaries?${range(current)}`),
      apiGet<SuiteSummaries>(`/api/suite-summaries?${range(previous)}`),
      apiGet<Page<RunSummary>>(`/api/runs?failing=true&size=5&${range(current)}`),
      apiGet<ResponseTimeChanges>(`/api/response-time-changes?limit=5&${range(current)}`),
      apiGet<Page<RunSummary>>(`/api/runs?status=RUNNING&size=${RUNNING_LIMIT}`),
      apiGet<MaintenanceWindow[]>("/api/maintenance-windows"),
      apiGet<UnstableCases>(`/api/unstable-cases?${range(current)}`),
      apiGet<MonitoringGaps>(`/api/monitoring-gaps?${range(current)}`),
      apiGet<Slowdown[]>("/api/slowdowns"),
    ]);
  const gaps = monitoringGaps.gaps;
  const maintenance = windows.filter((window) => window.active);
  const at = new Date();
  const now = totals(summaries.suites);
  const before = totals(previousSummaries.suites);
  const comparedTo = `이전 ${days}일 대비`;
  // 이전 기간에 실행이 전혀 없으면 비교하지 않음 (0 대비 변화는 의미 없음)
  const comparable = before.runs > 0;
  const slower = changes.changes.filter((change) => change.change > 0);

  return (
    <div className="space-y-4">
      <PageHeader
        title="대시보드"
        description={
          <span className="font-mono text-xs">
            {current.from} ~ {current.to} KST
          </span>
        }
        actions={<PeriodLinks days={days} />}
      />

      {maintenance.length > 0 && (
        <Panel title="점검 중인 환경" count={maintenance.length} meta="스케줄 실행 건너뜀">
          <ul className="divide-y text-sm">
            {maintenance.map((window) => (
              <li key={window.id} className="flex flex-wrap items-center gap-x-3 gap-y-1 px-4 py-2.5">
                <Link href={`/projects/${window.projectId}`} className="font-medium hover:underline">
                  {window.environment.name}
                </Link>
                <span className="font-mono text-xs text-muted-foreground">~{formatEnd(window.endsAt, at)} KST</span>
                {window.reason && <span className="text-muted-foreground">{window.reason}</span>}
              </li>
            ))}
          </ul>
        </Panel>
      )}

      {running.totalElements > 0 && (
        <Panel
          title="진행 중인 실행"
          count={running.totalElements}
          action={
            running.totalElements > RUNNING_LIMIT && (
              <Button asChild variant="ghost" size="sm">
                <Link href="/runs?status=RUNNING">모두 보기</Link>
              </Button>
            )
          }
        >
          {/* 끝나면 목록에서 빠지도록 진행 중인 실행이 있는 동안만 새로 고침 */}
          <AutoRefresh intervalMs={RUNNING_REFRESH_MS} />
          <Table aria-label="진행 중인 실행">
            <TableHeader>
              <TableRow>
                <TableHead>스위트</TableHead>
                <TableHead className="hidden md:table-cell">환경</TableHead>
                <TableHead>주체</TableHead>
                <TableHead>진행</TableHead>
                <TableHead className="hidden md:table-cell">시작 (KST)</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {running.content.map((run) => (
                <TableRow key={run.id}>
                  <TableCell className="whitespace-normal">
                    <Link href={`/runs/${run.id}`} className="font-medium hover:underline">
                      #{run.id} {run.suite.name}
                    </Link>
                    <div className="text-xs text-muted-foreground">
                      {run.project.name}
                      {run.testCase && <> · 케이스 단건: {run.testCase.name}</>}
                      {run.throughCase && <> · {run.throughCase.name}까지</>}
                      {run.rerunOf !== null && <> · #{run.rerunOf} 실패 다시 실행</>}
                    </div>
                  </TableCell>
                  <TableCell className="hidden md:table-cell">{run.environment.name}</TableCell>
                  <TableCell>
                    <TriggerBadge trigger={run.trigger} />
                    {run.triggeredBy && <div className="text-xs text-muted-foreground">{run.triggeredBy.name}</div>}
                  </TableCell>
                  <TableCell className="font-mono text-xs tabular-nums">
                    {formatProgress(run.summary.total, run.plannedCount)} 완료
                  </TableCell>
                  <TableCell className="hidden font-mono text-xs md:table-cell">
                    {formatDateTime(run.startedAt)}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Panel>
      )}

      {/* 응답시간 악화 알림 후 아직 회복하지 않은 케이스. 기간과 무관한 지금 상태라 없으면 표시하지 않음 */}
      {slowdowns.length > 0 && (
        <Panel title="지금 느린 케이스" count={slowdowns.length} meta="응답시간 악화 알림 후 회복 전">
          <SlowdownTable slowdowns={slowdowns} limit={SLOWDOWN_LIMIT} />
        </Panel>
      )}

      {/* 요약 수치를 읽기 전에 빠진 기간이 있음을 알림. 공백이 없으면 표시하지 않음 */}
      {gaps.length > 0 && (
        <Panel
          title="모니터링 공백"
          count={gaps.length}
          meta={`합계 ${formatGapDuration(totalGapDuration(gaps))} · 실패 알림 없었을 수 있음`}
        >
          <MonitoringGapList gaps={gaps} limit={GAP_LIMIT} />
        </Panel>
      )}

      <section
        aria-label="요약"
        className="grid divide-y rounded-lg border bg-card sm:grid-cols-3 sm:divide-x sm:divide-y-0"
      >
        <StatTile
          label="실행"
          value={formatCount(now.runs)}
          delta={comparable ? delta(now.runs, before.runs, (value) => `${formatCount(value)}회`, null) : null}
          comparedTo={comparedTo}
        />
        <StatTile
          label="통과율"
          value={formatPercent(now.passRate)}
          delta={
            comparable ? delta(now.passRate, before.passRate, (value) => `${(value * 100).toFixed(1)}%p`, true) : null
          }
          comparedTo={comparedTo}
        />
        <StatTile
          label="실패·오류가 있는 실행"
          value={formatCount(now.failingRuns)}
          delta={
            comparable ? delta(now.failingRuns, before.failingRuns, (value) => `${formatCount(value)}회`, false) : null
          }
          comparedTo={comparedTo}
        />
      </section>

      <Panel title="스위트별 통과율" count={summaries.suites.length} meta="낮은 순">
        {summaries.suites.length === 0 ? (
          <PanelEmpty
            message={
              <>
                등록된 스위트가 없습니다{" "}
                <Link href="/projects" className="underline">
                  프로젝트로 이동
                </Link>
              </>
            }
          />
        ) : (
          <Table aria-label="스위트별 통과율">
            <TableHeader>
              <TableRow>
                <TableHead>스위트</TableHead>
                <TableHead className="text-right">실행</TableHead>
                {/* 좁은 화면에서는 통과율이 보이도록 결과·최근 실행 열을 숨김 */}
                <TableHead className="hidden md:table-cell">결과</TableHead>
                <TableHead className="text-right">통과율</TableHead>
                <TableHead className="hidden md:table-cell">최근 실행 (KST)</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {byPassRate(summaries.suites).map((summary) => (
                <TableRow key={summary.suite.id}>
                  <TableCell className="whitespace-normal">
                    <Link href={`/suites/${summary.suite.id}`} className="font-medium hover:underline">
                      {summary.suite.name}
                    </Link>
                    <div className="text-xs text-muted-foreground">{summary.project.name}</div>
                  </TableCell>
                  <TableCell className="text-right font-mono text-xs tabular-nums">
                    {formatCount(summary.runs)}
                  </TableCell>
                  <TableCell className="hidden md:table-cell">
                    {summary.results.total > 0 ? <RunCountBadges counts={summary.results} /> : "–"}
                  </TableCell>
                  <TableCell>
                    <PassRateMeter rate={summary.passRate} label={`${summary.suite.name} 통과율`} />
                  </TableCell>
                  <TableCell className="hidden md:table-cell">
                    {summary.lastRun ? (
                      <Link href={`/runs/${summary.lastRun.id}`} className="flex items-center gap-2 hover:underline">
                        <StatusBadge status={summary.lastRun.status} />
                        <span className="font-mono text-xs">{formatDateTime(summary.lastRun.startedAt)}</span>
                      </Link>
                    ) : (
                      <span className="text-muted-foreground">실행 전</span>
                    )}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </Panel>

      <div className="grid gap-4 lg:grid-cols-2">
        <Panel
          title="최근 실패·오류"
          meta="최신순"
          action={
            <Button asChild variant="ghost" size="sm">
              <Link href={`/runs?failing=true&from=${current.from}&to=${current.to}`}>모두 보기</Link>
            </Button>
          }
        >
          {failingRuns.content.length === 0 ? (
            <PanelEmpty message="기간 안에 실패·오류가 있는 실행이 없습니다" />
          ) : (
            <ul className="divide-y" aria-label="최근 실패·오류 실행">
              {failingRuns.content.map((run) => (
                <li key={run.id}>
                  <Link href={`/runs/${run.id}`} className="block space-y-1 px-4 py-2.5 text-sm hover:bg-muted/50">
                    <div className="flex items-center justify-between gap-2">
                      <span className="font-medium">
                        #{run.id} {run.suite.name}
                        {run.testCase && (
                          <span className="font-normal text-muted-foreground"> · {run.testCase.name}</span>
                        )}
                      </span>
                      <RunCountBadges counts={run.summary} />
                    </div>
                    <div className="text-xs text-muted-foreground">
                      {run.environment.name} · <span className="font-mono">{formatDateTime(run.startedAt)}</span>
                    </div>
                  </Link>
                </li>
              ))}
            </ul>
          )}
        </Panel>

        <Panel title="응답시간이 나빠진 케이스" meta={`p95 · ${changes.previousFrom} ~ ${changes.previousTo} 대비`}>
          {slower.length === 0 ? (
            <PanelEmpty message="이전 기간보다 느려진 케이스가 없습니다" />
          ) : (
            <Table aria-label="응답시간이 나빠진 케이스">
              <TableHeader>
                <TableRow>
                  <TableHead>케이스</TableHead>
                  <TableHead className="text-right">p95</TableHead>
                  <TableHead className="text-right">변화</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {slower.map((change) => (
                  <TableRow key={`${change.testCase.id}-${change.environment.id}`}>
                    <TableCell className="whitespace-normal">
                      <Link
                        href={`/suites/${change.suite.id}?environmentId=${change.environment.id}&days=${days}&caseId=${change.testCase.id}#response-times`}
                        className="font-medium hover:underline"
                      >
                        {change.testCase.name}
                      </Link>
                      <div className="text-xs text-muted-foreground">
                        {change.suite.name} · {change.environment.name}
                      </div>
                    </TableCell>
                    <TableCell className="text-right font-mono text-xs whitespace-nowrap tabular-nums">
                      <span className="text-muted-foreground">{formatMs(change.previousP95)} → </span>
                      <span className="font-medium">{formatMs(change.currentP95)}</span>
                    </TableCell>
                    <TableCell className="text-right font-mono text-xs text-(--delta-bad) tabular-nums">
                      {formatChange(change.change)}
                      <span className="sr-only"> 느려짐</span>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </Panel>
      </div>

      <Panel title="불안정 케이스" count={unstable.cases.length} meta="실패 구간·재시도 2회 이상">
        {unstable.cases.length === 0 ? (
          <PanelEmpty message="기간 안에 불안정한 케이스가 없습니다" />
        ) : (
          <UnstableCaseTable cases={unstable.cases} from={current.from} to={current.to} limit={UNSTABLE_LIMIT} />
        )}
      </Panel>
    </div>
  );
}

/** 기간 선택. 한 줄로 화면 전체에 적용 */
function PeriodLinks({ days }: { days: DashboardPeriod }) {
  return (
    <nav aria-label="기간" className="flex rounded-md border bg-card p-0.5">
      {DASHBOARD_PERIODS.map((period) => (
        <Link
          key={period}
          href={`/?days=${period}`}
          aria-current={period === days ? "page" : undefined}
          className={cn(
            "rounded-sm px-3 py-1 font-mono text-xs",
            period === days ? "bg-accent font-medium text-foreground" : "text-muted-foreground hover:text-foreground",
          )}
        >
          <span className="sr-only">최근 </span>
          {period}일
        </Link>
      ))}
    </nav>
  );
}

function single(value: string | string[] | undefined): string | undefined {
  return Array.isArray(value) ? value[0] : value;
}
