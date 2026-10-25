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
  confirmLabel = "삭제",
}: {
  menuId: string;
  title: string;
  description: string;
  action: () => Promise<ActionState>;
  /** 확인 버튼 이름. 삭제가 곧 종료·취소인 대상용 (예: 점검 시간 "지금 종료") */
  confirmLabel?: string;
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
            {pending ? `${confirmLabel} 중…` : confirmLabel}
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
