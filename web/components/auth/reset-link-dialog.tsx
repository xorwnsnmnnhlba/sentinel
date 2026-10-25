"use client";

import { useState, useTransition } from "react";

import { IssuedLinkBox } from "@/components/auth/issued-link";
import { useMenuDialog } from "@/components/catalog/action-menu";
import { FormError } from "@/components/catalog/form-dialog";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { createPasswordReset, type LinkState } from "@/lib/actions/users";

/**
 * 비밀번호 재설정 링크 발급. 사용자 행의 "⋯" 메뉴(menuId)로 열림. 관리자가 새 비밀번호를 정하지 않고 사용자가 직접 입력
 */
export function ResetLinkDialog({
  menuId,
  userId,
  email,
  baseUrl,
}: {
  menuId: string;
  userId: number;
  email: string;
  baseUrl: string;
}) {
  const menu = useMenuDialog(menuId);
  const [state, setState] = useState<LinkState>();
  const [pending, startTransition] = useTransition();

  function issue() {
    startTransition(async () => {
      const result = await createPasswordReset(baseUrl, userId);
      // await 뒤의 상태 변경도 전환으로 묶어 결과와 진행 상태 해제를 한 번에 반영 (React useTransition 문서의 알려진 제약)
      startTransition(() => setState(result));
    });
  }

  return (
    <Dialog
      open={menu?.open ?? false}
      onOpenChange={(next) => {
        menu?.setOpen(next);
        setState(undefined);
      }}
    >
      <DialogContent onCloseAutoFocus={menu?.returnFocus}>
        <DialogHeader>
          <DialogTitle>비밀번호 재설정 링크</DialogTitle>
          <DialogDescription>
            {email} 계정의 재설정 링크를 만듭니다. 링크를 쓰면 해당 사용자의 모든 세션이 종료됩니다
          </DialogDescription>
        </DialogHeader>
        <FormError message={state?.error} />
        {state?.link ? (
          <IssuedLinkBox link={state.link} expiresAt={state.expiresAt} />
        ) : (
          <Button type="button" onClick={issue} disabled={pending} className="self-end">
            {pending ? "발급 중…" : "링크 발급"}
          </Button>
        )}
      </DialogContent>
    </Dialog>
  );
}
