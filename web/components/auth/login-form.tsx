"use client";

import { useActionState } from "react";

import { FormError, submitKeepingValues } from "@/components/catalog/form-dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { login } from "@/lib/actions/auth";

/**
 * 로그인 폼. 실패해도 입력한 이메일은 유지하고 비밀번호만 비움
 */
export function LoginForm({ from }: { from?: string }) {
  const [state, formAction, pending] = useActionState(login, undefined);

  return (
    <form method="post" onSubmit={(event) => submitKeepingValues(event, formAction)} className="space-y-4">
      {from && <input type="hidden" name="from" value={from} />}
      <div className="space-y-2">
        <Label htmlFor="email">이메일</Label>
        <Input id="email" name="email" type="email" autoComplete="username" required autoFocus />
      </div>
      <div className="space-y-2">
        <Label htmlFor="password">비밀번호</Label>
        <Input id="password" name="password" type="password" autoComplete="current-password" required />
      </div>
      <FormError message={state?.error} />
      <Button type="submit" className="w-full" disabled={pending}>
        {pending ? "로그인 중…" : "로그인"}
      </Button>
    </form>
  );
}
