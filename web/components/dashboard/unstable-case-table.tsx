import Link from "next/link";

import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import type { UnstableCase } from "@/lib/api/types";
import { unstableReason } from "@/lib/unstable";

/**
 * 대시보드의 불안정 케이스 (실패 구간·재시도가 많은 순, 최대 limit건).
 * 케이스를 누르면 그 스위트·환경의 기간 실행 이력으로 이동해 실패·복구 흐름 확인
 * @param from 기간 시작일 (YYYY-MM-DD, KST)
 * @param to 기간 종료일
 */
export function UnstableCaseTable({
  cases,
  from,
  to,
  limit,
}: {
  cases: UnstableCase[];
  from: string;
  to: string;
  limit: number;
}) {
  return (
    <div className="space-y-2">
      <div className="rounded-xl border">
        <Table aria-label="불안정 케이스">
          <TableHeader>
            <TableRow>
              <TableHead>케이스</TableHead>
              <TableHead>이유</TableHead>
              <TableHead className="text-right">실패 / 실행</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {cases.slice(0, limit).map((item) => (
              <TableRow key={`${item.testCase.id}-${item.environment.id}`}>
                <TableCell className="whitespace-normal">
                  <Link
                    href={`/runs?suiteId=${item.suite.id}&environmentId=${item.environment.id}&from=${from}&to=${to}`}
                    className="font-medium hover:underline"
                  >
                    {item.testCase.name}
                  </Link>
                  <div className="text-xs text-muted-foreground">
                    {item.suite.name} · {item.environment.name}
                  </div>
                </TableCell>
                <TableCell className="text-sm">{unstableReason(item)}</TableCell>
                <TableCell className="text-right text-xs whitespace-nowrap tabular-nums">
                  {item.failures} / {item.runs}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
      {cases.length > limit && (
        <p className="text-xs text-muted-foreground">
          {cases.length}건 중 {limit}건 (실패 구간·재시도가 많은 순)
        </p>
      )}
    </div>
  );
}
