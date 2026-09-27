import { notFound } from "next/navigation";

import { Empty, SectionHeader } from "@/components/catalog/section";
import { PageHeader } from "@/components/page-header";
import {
  RunCountBadges,
  SingleCaseBadge,
  StatusBadge,
  ThroughCaseBadge,
  TriggerBadge,
  VerdictBadge,
} from "@/components/run-badges";
import { DriftBadge, highestLevel } from "@/components/runs/drift";
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
  // 실패·오류 케이스와 호환이 깨지는 구조 변화가 있는 케이스는 펼친 상태로 표시
  const openByDefault = run.results
    .filter((result) => result.verdict !== "PASSED" || highestLevel(result.drift) === "BREAKING")
    .map((result) => String(result.id));

  return (
    <div className="space-y-6">
      <div className="space-y-3">
        <PageHeader
          back={{ href: "/runs", label: "실행 이력" }}
          title={
            <span className="inline-flex flex-wrap items-center gap-2">
              <span>
                <span className="text-muted-foreground">#{run.id}</span> {run.suiteName}
              </span>
              <TriggerBadge trigger={run.trigger} />
              {run.testCase && <SingleCaseBadge />}
              {run.throughCase && <ThroughCaseBadge />}
              <StatusBadge status={run.status} />
            </span>
          }
        />
        <dl className="flex flex-wrap gap-x-6 gap-y-1 text-sm text-muted-foreground">
          {run.testCase && <Meta label="케이스" value={run.testCase.name} />}
          {run.throughCase && <Meta label="마지막 케이스" value={run.throughCase.name} />}
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

      <section className="space-y-3">
        {/* 케이스 이름(h3, 펼침 버튼)의 상위 제목. 없으면 h1 다음이 h3라 제목 순서가 끊김 */}
        <SectionHeader
          title="케이스별 결과"
          count={run.results.length}
          description="실행 순서대로 표시. 실패·오류 케이스는 펼친 상태"
        />
        {run.results.length === 0 ? (
          <Empty message="실행된 케이스가 없습니다" />
        ) : (
          <Accordion type="multiple" defaultValue={openByDefault} className="rounded-xl border px-4">
            {run.results.map((result) => (
              <AccordionItem key={result.id} value={String(result.id)}>
                <AccordionTrigger className="hover:no-underline">
                  <div className="flex min-w-0 flex-1 items-center gap-3">
                    <VerdictBadge verdict={result.verdict} />
                    <span className="truncate font-medium">{result.testCaseName}</span>
                    <DriftBadge drift={result.drift} />
                    <span className="ml-auto flex shrink-0 items-center gap-3 font-mono text-xs text-muted-foreground">
                      <span>{result.request.method}</span>
                      {result.response && <span>{result.response.statusCode}</span>}
                      <span>{formatElapsed(result.elapsedMs)}</span>
                    </span>
                  </div>
                </AccordionTrigger>
                <AccordionContent>
                  <ResultDetail
                    result={result}
                    rawPurged={run.rawPurgedAt !== null}
                    singleCase={run.testCase !== null}
                  />
                </AccordionContent>
              </AccordionItem>
            ))}
          </Accordion>
        )}
      </section>
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
