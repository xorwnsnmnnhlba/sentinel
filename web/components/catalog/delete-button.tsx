"use client";

import { Trash2 } from "lucide-react";
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
 * 삭제 확인 후 실행. 삭제는 소프트 삭제(보관)이며 하위 항목 연쇄 보관, 실행 이력 유지
 */
export function DeleteButton({
  title,
  description,
  action,
  label,
}: {
  title: string;
  description: string;
  action: () => Promise<ActionState>;
  /** 지정 시 텍스트 버튼, 미지정 시 아이콘 버튼 */
  label?: string;
}) {
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
        {label ? (
          <Button variant="destructive" size="sm">
            {label}
          </Button>
        ) : (
          <Button variant="ghost" size="icon-sm" aria-label={title}>
            <Trash2 />
          </Button>
        )}
      </AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>{title}</AlertDialogTitle>
          <AlertDialogDescription>{description}</AlertDialogDescription>
        </AlertDialogHeader>
        <FormError message={error} />
        <AlertDialogFooter>
          <AlertDialogCancel disabled={pending}>취소</AlertDialogCancel>
          <Button variant="destructive" onClick={confirm} disabled={pending}>
            {pending ? "삭제 중…" : "삭제"}
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
