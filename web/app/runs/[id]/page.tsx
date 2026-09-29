import Link from "next/link";
import { notFound } from "next/navigation";

import { Empty, SectionHeader } from "@/components/catalog/section";
import { AutoRefresh } from "@/components/auto-refresh";
import { PageHeader } from "@/components/page-header";
import {
  RerunBadge,
  RunCountBadges,
  SingleCaseBadge,
  StatusBadge,
  SupportingBadge,
  ThroughCaseBadge,
  TriggerBadge,
  VerdictBadge,
} from "@/components/run-badges";
import { DriftBadge, highestLevel } from "@/components/runs/drift";
import { CancelRunButton } from "@/components/runs/cancel-run-button";
import { ChangeBadge, ChangeSummary } from "@/components/runs/run-change";
import { RerunFailedForm } from "@/components/runs/rerun-failed-form";
import { RunProgress } from "@/components/runs/run-progress";
import { ResultDetail } from "@/components/runs/result-detail";
import { Accordion, AccordionContent, AccordionItem, AccordionTrigger } from "@/components/ui/accordion";
import { cancelRun, rerunFailed } from "@/lib/actions/catalog";
import { apiGetOrNotFound } from "@/lib/api/server";
import type { RunDetail } from "@/lib/api/types";
import { formatDateTime, formatElapsed, formatProgress, formatRunDuration } from "@/lib/format";

/**
 * 중지 뒤 진행 중이던 케이스 결과가 기록될 때까지 새로 고치는 시간. 케이스 1건의 최대 소요(연결 5초 + 응답 대기 30초)에 여유
 */
const CANCEL_SETTLE_MS = 40_000;

/** 진행 중·중지 직후 새로 고침 주기 (결과 화면의 진행 표시와 같음) */
const REFRESH_MS = 1500;

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
  const failing = run.summary.failed + run.summary.errors;

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
              {run.rerunOf !== null && <RerunBadge />}
              <StatusBadge status={run.status} />
            </span>
          }
          actions={
            run.status === "RUNNING" ? (
              <CancelRunButton action={cancelRun.bind(null, run.id)} />
            ) : (
              failing > 0 && <RerunFailedForm action={rerunFailed.bind(null, run.id)} />
            )
          }
        />
        <dl className="flex flex-wrap gap-x-6 gap-y-1 text-sm text-muted-foreground">
          {run.testCase && <Meta label="케이스" value={run.testCase.name} />}
          {run.throughCase && <Meta label="마지막 케이스" value={run.throughCase.name} />}
          {run.rerunOf !== null && (
            <Meta
              label="원래 실행"
              value={
                <Link href={`/runs/${run.rerunOf}`} className="underline-offset-4 hover:underline">
                  #{run.rerunOf}
                </Link>
              }
            />
          )}
          <Meta label="환경" value={run.environmentName} />
          {run.triggeredBy && <Meta label="실행" value={run.triggeredBy.name} />}
          {run.canceledBy && <Meta label="중지" value={run.canceledBy.name} />}
          <Meta label="시작" value={formatDateTime(run.startedAt)} />
          <Meta label="소요" value={formatRunDuration(run.startedAt, run.finishedAt)} />
        </dl>
        <RunCountBadges counts={run.summary} />
        {run.status === "RUNNING" ? (
          <RunProgress done={run.results.length} planned={run.plannedCount} />
        ) : (
          <>
            {run.status === "CANCELED" &&
              run.finishedAt &&
              (run.plannedCount === null || run.results.length < run.plannedCount) && (
                // 중지 시점에 보낸 케이스가 끝나면 결과가 추가되므로 잠시 더 새로 고침
                <AutoRefresh
                  intervalMs={REFRESH_MS}
                  until={new Date(Date.parse(run.finishedAt) + CANCEL_SETTLE_MS).toISOString()}
                />
              )}
            {run.status === "CANCELED" && (
              <p className="text-sm">
                중지됨 · {formatProgress(run.results.length, run.plannedCount)} 실행. 남은 케이스는 보내지 않았습니다
              </p>
            )}
            <ChangeSummary run={run} />
          </>
        )}
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
          description={
            run.rerunOf === null
              ? "실행 순서대로 표시. 실패·오류 케이스는 펼친 상태"
              : "원래 실행에서 실패·오류가 난 케이스를 실행 순서대로 다시 실행. '함께 실행'은 그 케이스가 쓰는 추출 값을 만드는 앞 케이스"
          }
        />
        {run.results.length === 0 ? (
          <Empty message={run.status === "RUNNING" ? "아직 끝난 케이스가 없습니다" : "실행된 케이스가 없습니다"} />
        ) : (
          // 진행 중 새로 고침은 펼침 상태를 유지하므로, 실행이 끝나면 다시 그려 실패 케이스를 펼침
          <Accordion key={run.status} type="multiple" defaultValue={openByDefault} className="rounded-xl border px-4">
            {run.results.map((result) => (
              <AccordionItem key={result.id} value={String(result.id)}>
                <AccordionTrigger className="hover:no-underline">
                  <div className="flex min-w-0 flex-1 items-center gap-3">
                    <VerdictBadge verdict={result.verdict} />
                    <span className="truncate font-medium">{result.testCaseName}</span>
                    {result.supporting && <SupportingBadge />}
                    <ChangeBadge change={result.change} />
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

function Meta({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="flex gap-1.5">
      <dt>{label}</dt>
      <dd className="text-foreground">{value}</dd>
    </div>
  );
}
