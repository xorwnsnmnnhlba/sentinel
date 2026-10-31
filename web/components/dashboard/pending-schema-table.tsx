import Link from "next/link";

import { DriftLevelCounts } from "@/components/runs/drift";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import type { PendingSchemaChange } from "@/lib/api/types";
import { formatDateTime } from "@/lib/format";

/**
 * 대시보드의 승인 대기 구조 변화 (가장 심한 등급 순, 최대 limit건).
 * 케이스를 누르면 그 결과의 실행 화면으로 이동 (승인 대기 케이스가 펼쳐져 있고 "구조" 탭에서 기준선에 반영)
 */
export function PendingSchemaTable({ changes, limit }: { changes: PendingSchemaChange[]; limit: number }) {
  return (
    <div>
      <Table aria-label="승인 대기 구조 변화">
        <TableHeader>
          <TableRow>
            <TableHead>케이스</TableHead>
            <TableHead>변화</TableHead>
            <TableHead className="hidden md:table-cell">최근 실행 (KST)</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {changes.slice(0, limit).map((change) => (
            <TableRow key={change.id}>
              <TableCell className="whitespace-normal">
                <Link href={`/runs/${change.run.id}`} className="font-medium hover:underline">
                  {change.testCase.name}
                </Link>
                <div className="text-xs text-muted-foreground">
                  {change.suite.name} · {change.environment.name}
                </div>
              </TableCell>
              <TableCell>
                <DriftLevelCounts changes={change.changes} />
              </TableCell>
              <TableCell className="hidden font-mono text-xs md:table-cell">
                {formatDateTime(change.run.startedAt)}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
      {changes.length > limit && (
        <p className="border-t px-4 py-2 text-xs text-muted-foreground">
          {changes.length}건 중 {limit}건 (심한 변화 순)
        </p>
      )}
    </div>
  );
}
