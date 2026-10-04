import Link from "next/link";

import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import type { Slowdown } from "@/lib/api/types";
import { formatDateTime } from "@/lib/format";
import { formatIncrease, formatP95Change, responseTimesHref } from "@/lib/slowdown";

/**
 * 대시보드의 지금 느린 케이스 (감지 당시 늘어난 비율이 큰 순, 최대 limit건).
 * 케이스를 누르면 스위트 화면의 그 케이스·환경 응답시간 추세로 이동
 */
export function SlowdownTable({ slowdowns, limit }: { slowdowns: Slowdown[]; limit: number }) {
  return (
    <div>
      <Table aria-label="지금 느린 케이스">
        <TableHeader>
          <TableRow>
            <TableHead>케이스</TableHead>
            <TableHead className="text-right">p95 (감지 당시)</TableHead>
            <TableHead className="text-right">변화</TableHead>
            <TableHead className="hidden md:table-cell">감지 (KST)</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {slowdowns.slice(0, limit).map((slowdown) => (
            <TableRow key={slowdown.id}>
              <TableCell className="whitespace-normal">
                <Link href={responseTimesHref(slowdown)} className="font-medium hover:underline">
                  {slowdown.testCase.name}
                </Link>
                <div className="text-xs text-muted-foreground">
                  {slowdown.suite.name} · {slowdown.environment.name}
                </div>
              </TableCell>
              <TableCell className="text-right font-mono text-xs whitespace-nowrap tabular-nums">
                {formatP95Change(slowdown)}
              </TableCell>
              <TableCell className="text-right font-mono text-xs text-(--delta-bad) tabular-nums">
                {formatIncrease(slowdown.increase)}
                <span className="sr-only"> 느려짐</span>
              </TableCell>
              <TableCell className="hidden font-mono text-xs md:table-cell">
                {formatDateTime(slowdown.detectedAt)}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
      {slowdowns.length > limit && (
        <p className="border-t px-4 py-2 text-xs text-muted-foreground">
          {slowdowns.length}건 중 {limit}건 (늘어난 비율이 큰 순)
        </p>
      )}
    </div>
  );
}
