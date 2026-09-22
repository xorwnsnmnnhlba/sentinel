import Link from "next/link";
import { notFound } from "next/navigation";

import { RunCountBadges, StatusBadge, TriggerBadge, VerdictBadge } from "@/components/run-badges";
import { ResultDetail } from "@/components/runs/result-detail";
import { Accordion, AccordionContent, AccordionItem, AccordionTrigger } from "@/components/ui/accordion";
import { apiGetOrNotFound } from "@/lib/api/server";
import type { RunDetail } from "@/lib/api/types";
import { formatDateTime, formatElapsed, formatRunDuration } from "@/lib/format";

export async function generateMetadata(props: PageProps<"/runs/[id]">) {
  const { id } = await props.params;
  return { title: `실행 #${id} · Sentinel` };
}

export default async function RunDetailPage(props: PageProps<"/runs/[id]">) {
  const { id } = await props.params;
  if (!/^\d+$/.test(id)) {
    notFound();
  }
  const run = await apiGetOrNotFound<RunDetail>(`/api/runs/${id}`);
  // 실패·오류 케이스는 펼친 상태로 표시
  const openByDefault = run.results.filter((result) => result.verdict !== "PASSED").map((result) => String(result.id));

  return (
    <div className="space-y-6">
      <div className="space-y-2">
        <Link href="/runs" className="text-sm text-muted-foreground hover:text-foreground">
          ← 실행 이력
        </Link>
        <div className="flex flex-wrap items-center gap-2">
          <h1 className="text-2xl font-semibold tracking-tight">
            #{run.id} {run.suiteName}
          </h1>
          <TriggerBadge trigger={run.trigger} />
          <StatusBadge status={run.status} />
        </div>
        <dl className="flex flex-wrap gap-x-6 gap-y-1 text-sm text-muted-foreground">
          <Meta label="환경" value={run.environmentName} />
          {run.triggeredBy && <Meta label="실행" value={run.triggeredBy.name} />}
          <Meta label="시작" value={formatDateTime(run.startedAt)} />
          <Meta label="소요" value={formatRunDuration(run.startedAt, run.finishedAt)} />
        </dl>
        <RunCountBadges counts={run.summary} />
      </div>

      {run.rawPurgedAt && (
        <p className="rounded-md bg-muted p-3 text-sm text-muted-foreground">
          보관기간이 지나 요청·응답 원문(헤더·본문)을 삭제한 실행입니다 ({formatDateTime(run.rawPurgedAt)} 삭제). 판정과
          규칙별 결과는 그대로 확인할 수 있습니다
        </p>
      )}

      {run.results.length === 0 ? (
        <p className="rounded-lg border border-dashed p-10 text-center text-sm text-muted-foreground">
          실행된 케이스가 없습니다
        </p>
      ) : (
        <Accordion type="multiple" defaultValue={openByDefault} className="rounded-lg border px-4">
          {run.results.map((result) => (
            <AccordionItem key={result.id} value={String(result.id)}>
              <AccordionTrigger className="hover:no-underline">
                <div className="flex min-w-0 flex-1 items-center gap-3">
                  <VerdictBadge verdict={result.verdict} />
                  <span className="truncate font-medium">{result.testCaseName}</span>
                  <span className="ml-auto flex shrink-0 items-center gap-3 font-mono text-xs text-muted-foreground">
                    <span>{result.request.method}</span>
                    {result.response && <span>{result.response.statusCode}</span>}
                    <span>{formatElapsed(result.elapsedMs)}</span>
                  </span>
                </div>
              </AccordionTrigger>
              <AccordionContent>
                <ResultDetail result={result} rawPurged={run.rawPurgedAt !== null} />
              </AccordionContent>
            </AccordionItem>
          ))}
        </Accordion>
      )}
    </div>
  );
}

function Meta({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex gap-1.5">
      <dt>{label}</dt>
      <dd className="text-foreground">{value}</dd>
    </div>
  );
}
