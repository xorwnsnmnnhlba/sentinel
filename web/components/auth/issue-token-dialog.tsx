"use client";

import { Plus } from "lucide-react";
import { useActionState, useState } from "react";

import { IssuedTokenBox } from "@/components/auth/issued-token";
import { FormError, submitKeepingValues } from "@/components/catalog/form-dialog";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { DEFAULT_VALIDITY_DAYS, MAX_TOKEN_NAME_LENGTH, VALIDITY_DAYS } from "@/lib/access-token";
import type { IssueState } from "@/lib/actions/access-tokens";

/**
 * API 토큰 발급. 발급하면 창을 닫기 전까지 토큰을 한 번만 보여 줌. 닫았다 다시 열면 새로 발급하는 폼
 */
export function IssueTokenDialog({ action }: { action: (state: IssueState, form: FormData) => Promise<IssueState> }) {
  const [open, setOpen] = useState(false);

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button size="sm">
          <Plus /> 토큰 발급
        </Button>
      </DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>API 토큰 발급</DialogTitle>
          <DialogDescription>
            실행 시작과 그 결과 조회만 할 수 있습니다. 토큰으로 시작한 실행은 내가 실행한 것으로 기록됩니다
          </DialogDescription>
        </DialogHeader>
        {open && <IssueForm action={action} />}
      </DialogContent>
    </Dialog>
  );
}

function IssueForm({ action }: { action: (state: IssueState, form: FormData) => Promise<IssueState> }) {
  const [state, formAction, pending] = useActionState(action, undefined);

  if (state?.token) {
    return <IssuedTokenBox token={state.token} expiresAt={state.expiresAt} />;
  }
  return (
    <form method="post" onSubmit={(event) => submitKeepingValues(event, formAction)} className="space-y-4">
      <div className="space-y-2">
        <Label htmlFor="token-name">이름</Label>
        <Input
          id="token-name"
          name="name"
          required
          maxLength={MAX_TOKEN_NAME_LENGTH}
          placeholder="GitHub Actions 배포 확인"
          aria-describedby="token-name-hint"
        />
        <p id="token-name-hint" className="text-xs text-muted-foreground">
          어디에 쓰는 토큰인지. 목록과 변경 이력에 표시됩니다
        </p>
      </div>
      <div className="space-y-2">
        <Label htmlFor="token-validity">유효 기간</Label>
        <NativeSelect id="token-validity" name="validityDays" className="w-full" defaultValue={DEFAULT_VALIDITY_DAYS}>
          {VALIDITY_DAYS.map((days) => (
            <NativeSelectOption key={days} value={days}>
              {days}일
            </NativeSelectOption>
          ))}
        </NativeSelect>
      </div>
      <FormError message={state?.error} />
      <DialogFooter>
        <Button type="submit" disabled={pending}>
          {pending ? "발급 중…" : "발급"}
        </Button>
      </DialogFooter>
    </form>
  );
}
