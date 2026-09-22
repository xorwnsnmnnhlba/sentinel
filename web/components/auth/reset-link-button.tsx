"use client";

import { KeyRound } from "lucide-react";
import { useState, useTransition } from "react";

import { IssuedLinkBox } from "@/components/auth/issued-link";
import { FormError } from "@/components/catalog/form-dialog";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import { createPasswordReset, type LinkState } from "@/lib/actions/users";

/**
 * 비밀번호 재설정 링크 발급. 관리자가 새 비밀번호를 정하지 않고 사용자가 직접 입력
 */
export function ResetLinkButton({ userId, email, baseUrl }: { userId: number; email: string; baseUrl: string }) {
  const [open, setOpen] = useState(false);
  const [state, setState] = useState<LinkState>();
  const [pending, startTransition] = useTransition();

  function issue() {
    startTransition(async () => setState(await createPasswordReset(baseUrl, userId)));
  }

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        setOpen(next);
        setState(undefined);
      }}
    >
      <DialogTrigger asChild>
        <Button variant="ghost" size="icon-sm" aria-label={`${email} 비밀번호 재설정 링크`}>
          <KeyRound />
        </Button>
      </DialogTrigger>
      <DialogContent>
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
          <Button type="button" onClick={issue} disabled={pending}>
            {pending ? "발급 중…" : "링크 발급"}
          </Button>
        )}
      </DialogContent>
    </Dialog>
  );
}
