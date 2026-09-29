"use client";

import { RotateCcw } from "lucide-react";
import { useActionState } from "react";

import { FormError } from "@/components/catalog/form-dialog";
import { Button } from "@/components/ui/button";
import type { ActionState } from "@/lib/actions/catalog";

/**
 * 끝난 실행에서 실패·오류가 난 케이스만 같은 환경에서 다시 실행. 결과 화면 머리의 주 버튼.
 * 시작하면 새 실행의 결과 화면으로 이동 (서버 액션의 redirect)
 */
export function RerunFailedForm({ action }: { action: () => Promise<ActionState> }) {
  const [state, formAction, pending] = useActionState(action, undefined);

  return (
    <form method="post" action={formAction} className="flex flex-col items-end gap-1.5">
      <Button type="submit" disabled={pending}>
        <RotateCcw />
        {pending ? "다시 실행 중…" : "실패만 다시 실행"}
      </Button>
      <FormError message={state?.error} />
    </form>
  );
}
