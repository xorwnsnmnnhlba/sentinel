"use client";

import { Plus } from "lucide-react";
import { useActionState, useState } from "react";

import { IssuedLinkBox } from "@/components/auth/issued-link";
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
import { invite } from "@/lib/actions/users";

/**
 * 초대 링크 발급. 메일을 보내지 않으므로 발급된 링크를 복사해 직접 전달
 */
export function InviteDialog({ baseUrl }: { baseUrl: string }) {
  const [open, setOpen] = useState(false);
  const [state, formAction, pending] = useActionState(invite.bind(null, baseUrl), undefined);

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button size="sm">
          <Plus /> 사용자 초대
        </Button>
      </DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>사용자 초대</DialogTitle>
          <DialogDescription>초대 링크를 만들어 전달하면 상대가 이름과 비밀번호를 정합니다</DialogDescription>
        </DialogHeader>
        <form onSubmit={(event) => submitKeepingValues(event, formAction)} className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor="invite-email">이메일</Label>
            <Input id="invite-email" name="email" type="email" required placeholder="member@example.com" />
          </div>
          <div className="space-y-2">
            <Label htmlFor="invite-role">역할</Label>
            <NativeSelect id="invite-role" name="role" className="w-full" defaultValue="MEMBER">
              <NativeSelectOption value="MEMBER">구성원 (프로젝트·실행 관리)</NativeSelectOption>
              <NativeSelectOption value="ADMIN">관리자 (사용자 초대·삭제 가능)</NativeSelectOption>
            </NativeSelect>
          </div>
          <FormError message={state?.error} />
          {state?.link && <IssuedLinkBox link={state.link} expiresAt={state.expiresAt} />}
          <DialogFooter>
            <Button type="submit" disabled={pending}>
              {pending ? "발급 중…" : state?.link ? "다시 발급" : "초대 링크 발급"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
