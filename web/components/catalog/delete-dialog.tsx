"use client";

import { useState, useTransition } from "react";

import { useMenuDialog } from "@/components/catalog/action-menu";
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
import { Button } from "@/components/ui/button";
import type { ActionState } from "@/lib/actions/catalog";

/**
 * 삭제 확인 후 실행. "⋯" 메뉴의 삭제 항목(menuId)으로 열림 (DESIGN.md: 파괴적 동작은 메뉴 안쪽)
 * 삭제는 소프트 삭제(보관)이며 하위 항목 연쇄 보관, 실행 이력 유지
 */
export function DeleteDialog({
  menuId,
  title,
  description,
  action,
}: {
  menuId: string;
  title: string;
  description: string;
  action: () => Promise<ActionState>;
}) {
  const menu = useMenuDialog(menuId);
  const [error, setError] = useState<string>();
  const [pending, startTransition] = useTransition();

  function setOpen(open: boolean) {
    menu?.setOpen(open);
    setError(undefined);
  }

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
    <AlertDialog open={menu?.open ?? false} onOpenChange={setOpen}>
      <AlertDialogContent onCloseAutoFocus={menu?.returnFocus}>
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
