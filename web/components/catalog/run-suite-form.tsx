"use client";

import { Play } from "lucide-react";
import { useActionState } from "react";

import { FormError, submitKeepingValues } from "@/components/catalog/form-dialog";
import { Button } from "@/components/ui/button";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import type { ActionState } from "@/lib/actions/catalog";
import type { EnvironmentItem } from "@/lib/api/types";

/**
 * 환경 선택 후 스위트 즉시 실행. 실행이 끝나면 결과 상세로 이동
 * 스위트 화면 머리의 주 버튼. 환경 선택과 실행 버튼을 한 덩어리로 붙여 표시 (DESIGN.md "버튼 배치")
 */
export function RunSuiteForm({
  environments,
  caseCount,
  action,
}: {
  environments: EnvironmentItem[];
  caseCount: number;
  action: (state: ActionState, form: FormData) => Promise<ActionState>;
}) {
  const [state, formAction, pending] = useActionState(action, undefined);

  if (environments.length === 0) {
    return <p className="text-sm text-muted-foreground">실행하려면 프로젝트에 환경을 먼저 등록해 주세요</p>;
  }
  return (
    <form
      method="post"
      onSubmit={(event) => submitKeepingValues(event, formAction)}
      className="flex flex-col items-end gap-1.5"
    >
      <div className="flex items-center">
        <Select
          name="environmentId"
          defaultValue={environments.length === 1 ? String(environments[0].id) : undefined}
          required
        >
          <SelectTrigger
            className="w-36 rounded-r-none border-r-0 border-primary bg-primary-raised text-primary-foreground data-placeholder:text-primary-foreground/70 dark:bg-primary-raised dark:hover:bg-primary-raised [&_svg]:text-primary-foreground/70!"
            aria-label="실행 환경"
          >
            <SelectValue placeholder="환경 선택" />
          </SelectTrigger>
          <SelectContent>
            {environments.map((environment) => (
              <SelectItem key={environment.id} value={String(environment.id)}>
                {environment.name}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        <Button type="submit" disabled={pending || caseCount === 0} className="rounded-l-none">
          <Play />
          {pending ? "실행 중…" : "지금 실행"}
        </Button>
      </div>
      {caseCount === 0 && <p className="text-xs text-muted-foreground">실행할 케이스가 없습니다</p>}
      <FormError message={state?.error} />
    </form>
  );
}
