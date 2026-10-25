"use client";

import { useState, useTransition } from "react";

import { Switch } from "@/components/ui/switch";
import { setScheduleEnabled } from "@/lib/actions/catalog";

/**
 * 스케줄 활성·비활성 전환. 활성화하면 현재 시각 기준으로 다음 실행 시각이 다시 계산됨
 */
export function ScheduleToggle({
  scheduleId,
  enabled,
  label,
}: {
  scheduleId: number;
  enabled: boolean;
  label: string;
}) {
  const [error, setError] = useState<string>();
  const [pending, startTransition] = useTransition();

  function change(next: boolean) {
    setError(undefined);
    startTransition(async () => {
      const result = await setScheduleEnabled(scheduleId, next);
      if (result?.error) {
        // await 뒤의 상태 변경도 전환으로 묶어 결과와 진행 상태 해제를 한 번에 반영
        startTransition(() => setError(result.error));
      }
    });
  }

  return (
    <div className="flex items-center gap-2">
      <Switch checked={enabled} disabled={pending} onCheckedChange={change} aria-label={label} />
      <span className="text-xs text-muted-foreground">{enabled ? "활성" : "비활성"}</span>
      {error && (
        <span role="alert" className="text-xs text-destructive">
          {error}
        </span>
      )}
    </div>
  );
}
