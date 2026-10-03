import Link from "next/link";

import { Panel, PanelEmpty } from "@/components/catalog/section";
import { TrendChart } from "@/components/response-times/trend-chart";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { apiGet } from "@/lib/api/server";
import type { EnvironmentItem, Page, ResponseTimeSummary, ResponseTimeTrend, RunSummary } from "@/lib/api/types";
import { formatMs, kstDateRange, parsePeriod, type Period, PERIODS, slowestCase } from "@/lib/response-times";
import { cn } from "@/lib/utils";

/** 스위트 화면의 응답시간 영역. 조건은 주소(?environmentId=&days=&caseId=)에 남아 새로고침·공유해도 유지 */
export async function ResponseTimes({
  suiteId,
  environments,
  hasCases,
  searchParams,
}: {
  suiteId: number;
  environments: EnvironmentItem[];
  hasCases: boolean;
  searchParams: Record<string, string | string[] | undefined>;
}) {
  // 응답을 받은 실행만 (타임아웃·연결 실패 제외)
  const meta = "p50·p95 · KST";
  if (environments.length === 0 || !hasCases) {
    return (
      <Panel id="response-times" title="응답시간" meta={meta}>
        <PanelEmpty message={environments.length === 0 ? "환경을 먼저 등록해 주세요" : "등록된 케이스가 없습니다"} />
      </Panel>
    );
  }

  const days = parsePeriod(single(searchParams.days));
  const environmentId = await chooseEnvironment(suiteId, environments, single(searchParams.environmentId));
  const { from, to } = kstDateRange(days);
  const range = `environmentId=${environmentId}&from=${from}&to=${to}`;
  const summary = await apiGet<ResponseTimeSummary>(`/api/response-time-summaries?suiteId=${suiteId}&${range}`);

  const requested = Number(single(searchParams.caseId));
  const selected = summary.cases.find((testCase) => testCase.caseId === requested) ?? slowestCase(summary.cases);
  const trend = selected
    ? await apiGet<ResponseTimeTrend>(`/api/response-time-trends?caseId=${selected.caseId}&${range}`)
    : null;
  const environmentName = environments.find((environment) => environment.id === environmentId)?.name;

  return (
    <Panel id="response-times" title="응답시간" meta={meta}>
      <div className="border-b px-4 py-3">
        <Filters
          suiteId={suiteId}
          environments={environments}
          environmentId={environmentId}
          days={days}
          caseId={selected?.caseId}
        />
      </div>

      <div className="border-b">
        <Table aria-label="케이스별 응답시간">
          <TableHeader>
            <TableRow>
              <TableHead>케이스</TableHead>
              <TableHead className="text-right">건수</TableHead>
              <TableHead className="text-right">p50</TableHead>
              <TableHead className="text-right">p95</TableHead>
              <TableHead className="text-right">최대</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {summary.cases.map((testCase) => {
              const current = testCase.caseId === selected?.caseId;
              return (
                <TableRow key={testCase.caseId} className={cn(current && "bg-muted/50")}>
                  <TableCell className="font-medium whitespace-normal">
                    <Link
                      href={`/suites/${suiteId}?environmentId=${environmentId}&days=${days}&caseId=${testCase.caseId}#response-times`}
                      aria-current={current ? "true" : undefined}
                      scroll={false}
                      className="hover:underline"
                    >
                      {testCase.name}
                    </Link>
                  </TableCell>
                  <TableCell className="text-right tabular-nums">{testCase.samples}</TableCell>
                  <TableCell className="text-right tabular-nums">{formatMs(testCase.p50)}</TableCell>
                  <TableCell className="text-right tabular-nums">{formatMs(testCase.p95)}</TableCell>
                  <TableCell className="text-right tabular-nums">{formatMs(testCase.max)}</TableCell>
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
      </div>

      {selected && trend ? (
        <div className="p-4">
          <TrendChart
            key={`${selected.caseId}-${environmentId}-${days}`}
            points={trend.points}
            title={`${selected.name} · ${environmentName} · 최근 ${days}일 일별 응답시간`}
          />
        </div>
      ) : (
        <PanelEmpty message={`최근 ${days}일 동안 ${environmentName} 환경에서 응답을 받은 실행이 없습니다`} />
      )}
    </Panel>
  );
}

/**
 * 조회 환경. 주소에 지정된 환경, 없으면 이 스위트의 최근 실행 환경, 실행이 없으면 첫 환경
 */
async function chooseEnvironment(
  suiteId: number,
  environments: EnvironmentItem[],
  requested: string | undefined,
): Promise<number> {
  const requestedId = Number(requested);
  if (environments.some((environment) => environment.id === requestedId)) {
    return requestedId;
  }
  const latest = await apiGet<Page<RunSummary>>(`/api/runs?suiteId=${suiteId}&size=1`);
  const recent = latest.content[0]?.environment.id;
  return environments.some((environment) => environment.id === recent) ? recent! : environments[0].id;
}

/** 조건은 한 줄에 모아 표와 차트 모두에 적용. GET 폼이라 주소에 남음 */
function Filters({
  suiteId,
  environments,
  environmentId,
  days,
  caseId,
}: {
  suiteId: number;
  environments: EnvironmentItem[];
  environmentId: number;
  days: Period;
  caseId?: number;
}) {
  return (
    <form
      method="get"
      action={`/suites/${suiteId}#response-times`}
      aria-label="응답시간 조건"
      className="flex flex-wrap items-end gap-3"
    >
      <div className="space-y-1.5">
        <Label htmlFor="response-time-period" className="text-xs text-muted-foreground">
          기간
        </Label>
        <NativeSelect id="response-time-period" name="days" defaultValue={String(days)} className="w-32">
          {PERIODS.map((period) => (
            <NativeSelectOption key={period} value={period}>
              최근 {period}일
            </NativeSelectOption>
          ))}
        </NativeSelect>
      </div>
      <div className="space-y-1.5">
        <Label htmlFor="response-time-environment" className="text-xs text-muted-foreground">
          환경
        </Label>
        <NativeSelect
          id="response-time-environment"
          name="environmentId"
          defaultValue={String(environmentId)}
          className="w-40"
        >
          {environments.map((environment) => (
            <NativeSelectOption key={environment.id} value={environment.id}>
              {environment.name}
            </NativeSelectOption>
          ))}
        </NativeSelect>
      </div>
      {caseId !== undefined && <input type="hidden" name="caseId" value={caseId} />}
      <Button type="submit" variant="outline">
        조회
      </Button>
    </form>
  );
}

function single(value: string | string[] | undefined): string | undefined {
  return Array.isArray(value) ? value[0] : value;
}
