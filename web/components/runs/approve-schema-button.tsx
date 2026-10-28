"use client";

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
  AlertDialogTrigger,
} from "@/components/ui/alert-dialog";
import { Button } from "@/components/ui/button";
import type { ActionState } from "@/lib/actions/catalog";

/**
 * 승인 대기 응답 구조를 기준선에 반영. 되돌릴 수 없으므로 확인 창에서 반영 뒤 달라지는 점을 안내
 */
export function ApproveSchemaButton({ action }: { action: () => Promise<ActionState> }) {
  const [open, setOpen] = useState(false);
  const [error, setError] = useState<string>();
  const [pending, startTransition] = useTransition();

  function confirm() {
    startTransition(async () => {
      const result = await action();
      // await 뒤의 상태 변경도 전환으로 묶어 결과와 진행 상태 해제를 한 번에 반영
      startTransition(() => {
        if (result?.error) {
          setError(result.error);
        } else {
          setOpen(false);
        }
      });
    });
  }

  return (
    <AlertDialog
      open={open}
      onOpenChange={(next) => {
        setOpen(next);
        setError(undefined);
      }}
    >
      <AlertDialogTrigger asChild>
        <Button variant="outline" size="sm">
          기준선에 반영
        </Button>
      </AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>기준선에 반영</AlertDialogTitle>
          <AlertDialogDescription>
            이 응답 구조를 기준선에 넣어 다음 실행부터 같은 변화를 보고하지 않습니다. 의도한 API 변경일 때만 반영해
            주세요. 반영한 뒤에는 되돌릴 수 없습니다.
          </AlertDialogDescription>
        </AlertDialogHeader>
        <FormError message={error} />
        <AlertDialogFooter>
          <AlertDialogCancel disabled={pending}>취소</AlertDialogCancel>
          <Button onClick={confirm} disabled={pending}>
            {pending ? "반영 중…" : "반영"}
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
