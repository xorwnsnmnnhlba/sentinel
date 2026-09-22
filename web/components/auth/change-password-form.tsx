"use client";

import { useActionState } from "react";

import { NewPasswordFields } from "@/components/auth/password-fields";
import { FormError, submitKeepingValues } from "@/components/catalog/form-dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { changePassword } from "@/lib/actions/users";

export function ChangePasswordForm() {
  const [state, formAction, pending] = useActionState(changePassword, undefined);

  return (
    <form onSubmit={(event) => submitKeepingValues(event, formAction)} className="max-w-sm space-y-4">
      <div className="space-y-2">
        <Label htmlFor="currentPassword">현재 비밀번호</Label>
        <Input id="currentPassword" name="currentPassword" type="password" autoComplete="current-password" required />
      </div>
      <NewPasswordFields name="newPassword" />
      <FormError message={state?.error} />
      <Button type="submit" disabled={pending}>
        {pending ? "변경 중…" : "비밀번호 변경"}
      </Button>
      <p className="text-xs text-muted-foreground">변경하면 모든 기기에서 로그아웃되고 다시 로그인해야 합니다</p>
    </form>
  );
}
