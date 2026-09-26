"use client";

import { startTransition, useActionState, useState } from "react";

import { useMenuDialog } from "@/components/catalog/action-menu";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import type { ActionState } from "@/lib/actions/catalog";

/**
 * 등록·수정 폼 다이얼로그. 성공 시 닫고, 실패 시 열린 채로 Spring 오류 메시지 표시.
 * 닫았다 다시 열면 입력값을 초기값으로 되돌리기 위해 폼을 새로 마운트
 *
 * 여는 방법: trigger(버튼) 또는 menuId("⋯" 메뉴 항목, {@link ActionMenu} 안에서만)
 */
export function FormDialog({
  trigger,
  menuId,
  title,
  description,
  submitLabel,
  action,
  children,
}: {
  trigger?: React.ReactNode;
  menuId?: string;
  title: string;
  description?: string;
  submitLabel: string;
  action: (state: ActionState, form: FormData) => Promise<ActionState>;
  children: React.ReactNode;
}) {
  const menu = useMenuDialog(menuId);
  const [ownOpen, setOwnOpen] = useState(false);
  const open = menu ? menu.open : ownOpen;
  const setOpen = menu ? menu.setOpen : setOwnOpen;
  return (
    <Dialog open={open} onOpenChange={setOpen}>
      {trigger && <DialogTrigger asChild>{trigger}</DialogTrigger>}
      <DialogContent onCloseAutoFocus={menu?.returnFocus}>
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          {description && <DialogDescription>{description}</DialogDescription>}
        </DialogHeader>
        {open && (
          <DialogForm action={action} submitLabel={submitLabel} onDone={() => setOpen(false)}>
            {children}
          </DialogForm>
        )}
      </DialogContent>
    </Dialog>
  );
}

function DialogForm({
  action,
  submitLabel,
  onDone,
  children,
}: {
  action: (state: ActionState, form: FormData) => Promise<ActionState>;
  submitLabel: string;
  onDone: () => void;
  children: React.ReactNode;
}) {
  const [state, formAction, pending] = useActionState(async (previous: ActionState, form: FormData) => {
    const result = await action(previous, form);
    if (!result?.error) {
      onDone();
    }
    return result;
  }, undefined);

  return (
    <form method="post" onSubmit={(event) => submitKeepingValues(event, formAction)} className="space-y-4">
      <div className="space-y-4">{children}</div>
      <FormError message={state?.error} />
      <DialogFooter>
        <DialogClose asChild>
          <Button type="button" variant="outline" disabled={pending}>
            취소
          </Button>
        </DialogClose>
        <Button type="submit" disabled={pending}>
          {pending ? "저장 중…" : submitLabel}
        </Button>
      </DialogFooter>
    </form>
  );
}

/**
 * 입력값을 유지한 채 제출. form action prop으로 제출하면 React가 제출 후 폼을 초기화하여 오류 시 입력값 유실
 */
export function submitKeepingValues(event: React.FormEvent<HTMLFormElement>, formAction: (form: FormData) => void) {
  event.preventDefault();
  const form = new FormData(event.currentTarget);
  startTransition(() => formAction(form));
}

export function FormError({ message }: { message?: string }) {
  if (!message) {
    return null;
  }
  return (
    <p role="alert" className="rounded-md bg-destructive/10 px-3 py-2 text-sm text-destructive">
      {message}
    </p>
  );
}
