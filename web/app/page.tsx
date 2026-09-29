import Link from "next/link";

import { AutoRefresh } from "@/components/auto-refresh";
import { PageHeader } from "@/components/page-header";
import { PassRateMeter } from "@/components/dashboard/pass-rate-meter";
import { StatTile } from "@/components/dashboard/stat-tile";
import { RunCountBadges, StatusBadge, TriggerBadge } from "@/components/run-badges";
import { Button } from "@/components/ui/button";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { apiGet } from "@/lib/api/server";
import type { MaintenanceWindow, Page, ResponseTimeChanges, RunSummary, SuiteSummaries } from "@/lib/api/types";
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
import { formatMs, kstDateRange } from "@/lib/response-times";
import { cn } from "@/lib/utils";

export const metadata = { title: "대시보드 · Sentinel" };

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

  const [summaries, previousSummaries, failingRuns, changes, running, windows] = await Promise.all([
    apiGet<SuiteSummaries>(`/api/suite-summaries?${range(current)}`),
    apiGet<SuiteSummaries>(`/api/suite-summaries?${range(previous)}`),
    apiGet<Page<RunSummary>>(`/api/runs?failing=true&size=5&${range(current)}`),
    apiGet<ResponseTimeChanges>(`/api/response-time-changes?limit=5&${range(current)}`),
    apiGet<Page<RunSummary>>(`/api/runs?status=RUNNING&size=${RUNNING_LIMIT}`),
    apiGet<MaintenanceWindow[]>("/api/maintenance-windows"),
  ]);
  const maintenance = windows.filter((window) => window.active);
  const at = new Date();
  const now = totals(summaries.suites);
  const before = totals(previousSummaries.suites);
  const comparedTo = `이전 ${days}일 대비`;
  // 이전 기간에 실행이 전혀 없으면 비교하지 않음 (0 대비 변화는 의미 없음)
  const comparable = before.runs > 0;
  const slower = changes.changes.filter((change) => change.change > 0);

  return (
    <div className="space-y-8">
      <PageHeader
        title="대시보드"
        description={`${current.from} ~ ${current.to} (KST) 실행 현황`}
        actions={<PeriodLinks days={days} />}
      />

      {maintenance.length > 0 && (
        <section className="space-y-3">
          <SectionTitle
            title={`점검 중인 환경 ${maintenance.length}곳`}
            description="점검이 끝날 때까지 이 환경의 스케줄 실행을 건너뜀 (실행 기록·실패 알림 없음)"
          />
          <ul className="divide-y rounded-xl border text-sm">
            {maintenance.map((window) => (
              <li key={window.id} className="flex flex-wrap items-center gap-x-3 gap-y-1 px-4 py-2.5">
                <Link href={`/projects/${window.projectId}`} className="font-medium hover:underline">
                  {window.environment.name}
                </Link>
                <span className="text-muted-foreground">~{formatEnd(window.endsAt, at)} (KST)</span>
                {window.reason && <span className="text-muted-foreground">· {window.reason}</span>}
              </li>
            ))}
          </ul>
        </section>
      )}

      {running.totalElements > 0 && (
        <section className="space-y-3">
          {/* 끝나면 목록에서 빠지도록 진행 중인 실행이 있는 동안만 새로 고침 */}
          <AutoRefresh intervalMs={RUNNING_REFRESH_MS} />
          <SectionTitle
            title={`진행 중인 실행 ${running.totalElements}건`}
            description="스케줄·수동 실행 모두. 끝나면 자동으로 목록에서 빠짐"
          />
          <div className="rounded-xl border">
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
                    <TableCell className="tabular-nums">
                      {formatProgress(run.summary.total, run.plannedCount)} 완료
                    </TableCell>
                    <TableCell className="hidden font-mono text-xs md:table-cell">
                      {formatDateTime(run.startedAt)}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
          {running.totalElements > RUNNING_LIMIT && (
            <Link href="/runs?status=RUNNING" className="text-sm underline-offset-4 hover:underline">
              진행 중인 실행 모두 보기
            </Link>
          )}
        </section>
      )}

      <section aria-label="요약" className="grid gap-4 sm:grid-cols-3">
        <StatTile
          label="실행"
          value={`${formatCount(now.runs)}회`}
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
          value={`${formatCount(now.failingRuns)}회`}
          delta={
            comparable ? delta(now.failingRuns, before.failingRuns, (value) => `${formatCount(value)}회`, false) : null
          }
          comparedTo={comparedTo}
        />
      </section>

      <section className="space-y-3">
        <SectionTitle title="스위트별 통과율" description="통과율 낮은 순. 통과율은 기간 안 실행의 결과 기준" />
        {summaries.suites.length === 0 ? (
          <Empty message="등록된 스위트가 없습니다" href="/projects" link="프로젝트로 이동" />
        ) : (
          <div className="rounded-xl border">
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
                    <TableCell className="text-right tabular-nums">{formatCount(summary.runs)}</TableCell>
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
          </div>
        )}
      </section>

      <div className="grid gap-8 lg:grid-cols-2">
        <section className="space-y-3">
          <SectionTitle
            title="최근 실패·오류"
            description="판정 실패나 오류가 있는 실행, 최신순"
            action={
              <Button asChild variant="ghost" size="sm">
                <Link href={`/runs?failing=true&from=${current.from}&to=${current.to}`}>모두 보기</Link>
              </Button>
            }
          />
          {failingRuns.content.length === 0 ? (
            <Empty message="기간 안에 실패·오류가 있는 실행이 없습니다" />
          ) : (
            <ul className="divide-y rounded-xl border" aria-label="최근 실패·오류 실행">
              {failingRuns.content.map((run) => (
                <li key={run.id}>
                  <Link href={`/runs/${run.id}`} className="block space-y-1 p-3 hover:bg-muted/50">
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
        </section>

        <section className="space-y-3">
          <SectionTitle
            title="응답시간이 나빠진 케이스"
            description={`p95 기준, ${changes.previousFrom} ~ ${changes.previousTo} 대비 (두 기간 모두 3건 이상)`}
          />
          {slower.length === 0 ? (
            <Empty message="이전 기간보다 느려진 케이스가 없습니다" />
          ) : (
            <div className="rounded-xl border">
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
                      <TableCell className="text-right text-xs whitespace-nowrap tabular-nums">
                        <span className="text-muted-foreground">{formatMs(change.previousP95)} → </span>
                        <span className="font-medium">{formatMs(change.currentP95)}</span>
                      </TableCell>
                      <TableCell className="text-right text-(--delta-bad) tabular-nums">
                        {formatChange(change.change)}
                        <span className="sr-only"> 느려짐</span>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
          )}
        </section>
      </div>
    </div>
  );
}

/** 기간 선택. 한 줄로 화면 전체에 적용 */
function PeriodLinks({ days }: { days: DashboardPeriod }) {
  return (
    <nav aria-label="기간" className="flex gap-1 rounded-lg bg-muted p-1">
      {DASHBOARD_PERIODS.map((period) => (
        <Link
          key={period}
          href={`/?days=${period}`}
          aria-current={period === days ? "page" : undefined}
          className={cn(
            "rounded-md px-3 py-1 text-sm",
            period === days
              ? "bg-background font-medium text-foreground shadow-sm"
              : "text-muted-foreground hover:text-foreground",
          )}
        >
          최근 {period}일
        </Link>
      ))}
    </nav>
  );
}

function SectionTitle({
  title,
  description,
  action,
}: {
  title: string;
  description: string;
  action?: React.ReactNode;
}) {
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

function Empty({ message, href, link }: { message: string; href?: string; link?: string }) {
  return (
    <p className="rounded-xl border border-dashed p-8 text-center text-sm text-muted-foreground">
      {message}
      {href && link && (
        <>
          {" "}
          <Link href={href} className="underline">
            {link}
          </Link>
        </>
      )}
    </p>
  );
}

function single(value: string | string[] | undefined): string | undefined {
  return Array.isArray(value) ? value[0] : value;
}
