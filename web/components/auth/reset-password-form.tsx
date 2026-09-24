"use client";

import { useActionState } from "react";

import { NewPasswordFields } from "@/components/auth/password-fields";
import { FormError, submitKeepingValues } from "@/components/catalog/form-dialog";
import { Button } from "@/components/ui/button";
import { resetPassword } from "@/lib/actions/users";

/** 링크로 새 비밀번호 설정. 저장 후에는 기존 세션이 모두 끊기므로 다시 로그인 */
export function ResetPasswordForm({ token }: { token: string }) {
  const [state, formAction, pending] = useActionState(resetPassword.bind(null, token), undefined);

  return (
    <form method="post" onSubmit={(event) => submitKeepingValues(event, formAction)} className="space-y-4">
      <NewPasswordFields label="새 비밀번호" />
      <FormError message={state?.error} />
      <Button type="submit" className="w-full" disabled={pending}>
        {pending ? "설정 중…" : "비밀번호 설정"}
      </Button>
    </form>
  );
}
