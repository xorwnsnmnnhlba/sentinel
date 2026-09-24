"use client";

import { useActionState } from "react";

import { NewPasswordFields } from "@/components/auth/password-fields";
import { FormError, submitKeepingValues } from "@/components/catalog/form-dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { acceptInvite } from "@/lib/actions/users";

/** 초대 수락. 계정을 만들고 바로 로그인 상태가 됨 */
export function AcceptInviteForm({ token }: { token: string }) {
  const [state, formAction, pending] = useActionState(acceptInvite.bind(null, token), undefined);

  return (
    <form method="post" onSubmit={(event) => submitKeepingValues(event, formAction)} className="space-y-4">
      <div className="space-y-2">
        <Label htmlFor="name">이름</Label>
        <Input id="name" name="name" maxLength={100} required autoFocus />
      </div>
      <NewPasswordFields label="비밀번호" />
      <FormError message={state?.error} />
      <Button type="submit" className="w-full" disabled={pending}>
        {pending ? "만드는 중…" : "계정 만들기"}
      </Button>
    </form>
  );
}
