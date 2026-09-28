"use client";

import { Square } from "lucide-react";
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
 * 진행 중인 실행 중지. 이미 보낸 요청은 되돌릴 수 없으므로 진행 중이던 케이스까지 기록된다는 점을 확인 창에서 안내
 */
export function CancelRunButton({ action }: { action: () => Promise<ActionState> }) {
  const [open, setOpen] = useState(false);
  const [error, setError] = useState<string>();
  const [pending, startTransition] = useTransition();

  function confirm() {
    startTransition(async () => {
      const result = await action();
      if (result?.error) {
        setError(result.error);
      } else {
        setOpen(false);
      }
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
          <Square /> 중지
        </Button>
      </AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>실행 중지</AlertDialogTitle>
          <AlertDialogDescription>
            진행 중인 케이스는 끝까지 실행해 기록하고, 남은 케이스는 보내지 않습니다. 이미 보낸 요청은 되돌릴 수
            없습니다.
          </AlertDialogDescription>
        </AlertDialogHeader>
        <FormError message={error} />
        <AlertDialogFooter>
          <AlertDialogCancel disabled={pending}>계속 실행</AlertDialogCancel>
          <Button variant="destructive" onClick={confirm} disabled={pending}>
            {pending ? "중지 중…" : "중지"}
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
