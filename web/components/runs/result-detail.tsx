import { VerdictBadge } from "@/components/run-badges";
import { Badge } from "@/components/ui/badge";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import type { Headers, RunResult } from "@/lib/api/types";
import { prettyBody } from "@/lib/format";

/**
 * 케이스 1건의 결과. 오류 사유, 판정 규칙별 결과, 요청·응답 원문
 * 보관기간이 지나 원문을 삭제한 실행(rawPurged)은 원문 대신 안내 문구 표시
 */
export function ResultDetail({ result, rawPurged = false }: { result: RunResult; rawPurged?: boolean }) {
  return (
    <div className="space-y-4">
      <p className="font-mono text-xs break-all text-muted-foreground">
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
                  <TableCell className="font-mono text-xs break-all whitespace-normal">{assertion.message}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}

      {result.request.url && rawPurged && (
        <p className="text-xs text-muted-foreground">보관기간이 지나 요청·응답 헤더와 본문을 삭제했습니다</p>
      )}

      {result.request.url && !rawPurged && (
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
