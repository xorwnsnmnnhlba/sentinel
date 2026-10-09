"use client";

import Link from "next/link";
import { useState, useTransition } from "react";

import { FormError } from "@/components/catalog/form-dialog";
import {
  AlertDialog,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import type { ActionState } from "@/lib/actions/catalog";
import { formatDateTime } from "@/lib/format";
import type { ScheduleDetail } from "@/lib/api/types";

type Change = { enabled?: boolean; skipHolidays?: boolean };

/**
 * 전체 스케줄 표. 골라서 켜기·끄기·휴일 건너뛰기를 한 번에 변경. 끄기는 감시가 멈추므로 확인 후
 * 변경 후 목록을 다시 받으면 선택은 비움
 */
export function ScheduleBulkTable({
  schedules,
  action,
}: {
  schedules: ScheduleDetail[];
  action: (scheduleIds: number[], change: Change) => Promise<ActionState>;
}) {
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [error, setError] = useState<string>();
  const [confirmingOff, setConfirmingOff] = useState(false);
  const [pending, startTransition] = useTransition();

  // 목록이 바뀌어(삭제·필터) 보이지 않는 스케줄은 선택에서 제외
  const chosen = schedules.map((schedule) => schedule.id).filter((id) => selected.has(id));
  const allChosen = schedules.length > 0 && chosen.length === schedules.length;

  function toggle(scheduleId: number, checked: boolean) {
    setSelected((current) => {
      const next = new Set(current);
      if (checked) {
        next.add(scheduleId);
      } else {
        next.delete(scheduleId);
      }
      return next;
    });
  }

  function apply(change: Change) {
    setError(undefined);
    startTransition(async () => {
      const result = await action(chosen, change);
      if (result?.error) {
        setError(result.error);
      } else {
        setSelected(new Set());
      }
      setConfirmingOff(false);
    });
  }

  return (
    <div>
      <div
        role="toolbar"
        aria-label="고른 스케줄 변경"
        className="flex flex-wrap items-center gap-2 border-b px-4 py-2"
      >
        <span className="mr-auto font-mono text-xs text-muted-foreground" aria-live="polite">
          {chosen.length}개 선택
        </span>
        <Button
          size="sm"
          variant="outline"
          disabled={pending || chosen.length === 0}
          onClick={() => apply({ enabled: true })}
        >
          켜기
        </Button>
        <Button
          size="sm"
          variant="outline"
          disabled={pending || chosen.length === 0}
          onClick={() => setConfirmingOff(true)}
        >
          끄기
        </Button>
        <Button
          size="sm"
          variant="outline"
          disabled={pending || chosen.length === 0}
          onClick={() => apply({ skipHolidays: true })}
        >
          휴일 건너뛰기 켜기
        </Button>
        <Button
          size="sm"
          variant="outline"
          disabled={pending || chosen.length === 0}
          onClick={() => apply({ skipHolidays: false })}
        >
          휴일 건너뛰기 끄기
        </Button>
      </div>
      {error && (
        <div className="border-b p-3">
          <FormError message={error} />
        </div>
      )}

      <Table aria-label="스케줄">
        <TableHeader>
          <TableRow>
            <TableHead className="w-10">
              <input
                type="checkbox"
                aria-label="모두 선택"
                checked={allChosen}
                disabled={schedules.length === 0}
                onChange={(event) =>
                  setSelected(event.target.checked ? new Set(schedules.map((schedule) => schedule.id)) : new Set())
                }
                className="size-4 accent-primary"
              />
            </TableHead>
            <TableHead>cron</TableHead>
            <TableHead>스위트</TableHead>
            <TableHead>환경</TableHead>
            <TableHead>다음 실행 (KST)</TableHead>
            <TableHead>마지막 실행 (KST)</TableHead>
            <TableHead>상태</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {schedules.map((schedule) => (
            <TableRow key={schedule.id} data-state={selected.has(schedule.id) ? "selected" : undefined}>
              <TableCell>
                <input
                  type="checkbox"
                  aria-label={`${schedule.suite.name} ${schedule.environment.name} ${schedule.cron} 선택`}
                  checked={selected.has(schedule.id)}
                  onChange={(event) => toggle(schedule.id, event.target.checked)}
                  className="size-4 accent-primary"
                />
              </TableCell>
              <TableCell className="font-mono text-xs">
                <div className="flex items-center gap-2">
                  {schedule.cron}
                  {schedule.skipHolidays && <Badge variant="outline">휴일 제외</Badge>}
                </div>
              </TableCell>
              <TableCell className="whitespace-normal">
                <Link href={`/suites/${schedule.suite.id}`} className="font-medium hover:underline">
                  {schedule.suite.name}
                </Link>
                <div className="text-xs text-muted-foreground">{schedule.project.name}</div>
              </TableCell>
              <TableCell>{schedule.environment.name}</TableCell>
              <TableCell className="font-mono text-xs">
                {schedule.enabled && schedule.nextRunAt ? formatDateTime(schedule.nextRunAt) : "-"}
              </TableCell>
              <TableCell className="font-mono text-xs text-muted-foreground">
                {schedule.lastTriggeredAt ? formatDateTime(schedule.lastTriggeredAt) : "-"}
              </TableCell>
              <TableCell>
                {schedule.enabled ? (
                  <Badge variant="secondary">활성</Badge>
                ) : (
                  <Badge variant="outline" className="text-muted-foreground">
                    비활성
                  </Badge>
                )}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>

      <AlertDialog open={confirmingOff} onOpenChange={(open) => !pending && setConfirmingOff(open)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>스케줄 {chosen.length}개 끄기</AlertDialogTitle>
            <AlertDialogDescription>
              끄는 동안 이 스케줄은 실행되지 않고 실패 알림도 오지 않습니다. 다시 켜면 그때부터 다음 실행 시각을
              계산합니다.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={pending}>취소</AlertDialogCancel>
            <Button variant="destructive" disabled={pending} onClick={() => apply({ enabled: false })}>
              {pending ? "끄는 중…" : "끄기"}
            </Button>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
