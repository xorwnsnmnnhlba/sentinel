import Link from "next/link";
import { notFound } from "next/navigation";

import { RunCountBadges, StatusBadge, TriggerBadge, VerdictBadge } from "@/components/run-badges";
import { Accordion, AccordionContent, AccordionItem, AccordionTrigger } from "@/components/ui/accordion";
import { Badge } from "@/components/ui/badge";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { ApiError, apiGet } from "@/lib/api/server";
import type { Headers, RunDetail, RunResult } from "@/lib/api/types";
import { formatDateTime, formatElapsed, formatRunDuration, prettyBody } from "@/lib/format";

export async function generateMetadata(props: PageProps<"/runs/[id]">) {
  const { id } = await props.params;
  return { title: `실행 #${id} · Sentinel` };
}

export default async function RunDetailPage(props: PageProps<"/runs/[id]">) {
  const { id } = await props.params;
  if (!/^\d+$/.test(id)) {
    notFound();
  }
  const run = await getRun(id);
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
          <Meta label="시작" value={formatDateTime(run.startedAt)} />
          <Meta label="소요" value={formatRunDuration(run.startedAt, run.finishedAt)} />
        </dl>
        <RunCountBadges counts={run.summary} />
      </div>

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
                <ResultDetail result={result} />
              </AccordionContent>
            </AccordionItem>
          ))}
        </Accordion>
      )}
    </div>
  );
}

async function getRun(id: string): Promise<RunDetail> {
  try {
    return await apiGet<RunDetail>(`/api/runs/${id}`);
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) {
      notFound();
    }
    throw error;
  }
}

function Meta({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex gap-1.5">
      <dt>{label}</dt>
      <dd className="text-foreground">{value}</dd>
    </div>
  );
}

function ResultDetail({ result }: { result: RunResult }) {
  return (
    <div className="space-y-4">
      <p className="break-all font-mono text-xs text-muted-foreground">
        {result.request.method} {result.request.url ?? "(전송 전 실패)"}
      </p>

      {result.errorReason && (
        <div className="rounded-md bg-amber-500/10 p-3 text-sm text-amber-800 dark:text-amber-300">
          <span className="font-semibold">{result.errorReason}</span>
          {result.errorMessage && <span className="ml-2 break-all">{result.errorMessage}</span>}
        </div>
      )}

      {result.assertionResults.length > 0 && (
        <div className="rounded-md border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead className="w-16">판정</TableHead>
                <TableHead>규칙</TableHead>
                <TableHead>실제 값</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {result.assertionResults.map((assertion, index) => (
                <TableRow key={index}>
                  <TableCell>
                    <VerdictBadge verdict={assertion.passed ? "PASSED" : "FAILED"} />
                  </TableCell>
                  <TableCell className="font-mono text-xs whitespace-normal">{assertion.description}</TableCell>
                  <TableCell className="font-mono text-xs whitespace-normal break-all">{assertion.message}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}

      {result.request.url && (
        <Tabs defaultValue={result.response ? "response" : "request"}>
          <TabsList>
            <TabsTrigger value="response" disabled={!result.response}>
              응답
            </TabsTrigger>
            <TabsTrigger value="request">요청</TabsTrigger>
          </TabsList>
          <TabsContent value="response" className="space-y-3">
            {result.response && (
              <>
                <HeaderTable headers={result.response.headers} />
                <Body body={result.response.body} truncated={result.response.bodyTruncated} />
              </>
            )}
          </TabsContent>
          <TabsContent value="request" className="space-y-3">
            <HeaderTable headers={result.request.headers} />
            <Body body={result.request.body} />
          </TabsContent>
        </Tabs>
      )}
    </div>
  );
}

function HeaderTable({ headers }: { headers: Headers | null }) {
  const entries = Object.entries(headers ?? {});
  if (entries.length === 0) {
    return <p className="text-xs text-muted-foreground">헤더 없음</p>;
  }
  return (
    <dl className="grid grid-cols-[minmax(8rem,auto)_1fr] gap-x-4 gap-y-1 font-mono text-xs">
      {entries.map(([name, values]) => (
        <div key={name} className="contents">
          <dt className="text-muted-foreground">{name}</dt>
          <dd className="break-all">{values.join(", ")}</dd>
        </div>
      ))}
    </dl>
  );
}

function Body({ body, truncated }: { body: string | null; truncated?: boolean }) {
  if (!body) {
    return <p className="text-xs text-muted-foreground">본문 없음</p>;
  }
  return (
    <div className="space-y-1">
      {truncated && (
        <Badge variant="outline" className="text-amber-700 dark:text-amber-400">
          64KB 초과로 잘림
        </Badge>
      )}
      <pre className="max-h-96 overflow-auto rounded-md bg-muted p-3 font-mono text-xs">{prettyBody(body)}</pre>
    </div>
  );
}
