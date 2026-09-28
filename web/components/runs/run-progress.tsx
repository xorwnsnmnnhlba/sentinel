"use client";

import { LoaderCircle } from "lucide-react";

import { usePeriodicRefresh } from "@/components/auto-refresh";
import { formatProgress } from "@/lib/format";

/** 새로 고침 주기(ms). 케이스마다 결과를 저장하므로 끝난 케이스가 차례로 나타남 */
export const REFRESH_INTERVAL_MS = 1500;

/**
 * 진행 중인 실행의 진행률. 화면을 주기적으로 다시 받아 끝난 케이스 결과와 진행률을 갱신
 * 실행이 끝나면 서버가 이 컴포넌트를 그리지 않으므로 새로 고침도 멈춤
 */
export function RunProgress({ done, planned }: { done: number; planned: number | null }) {
  usePeriodicRefresh(REFRESH_INTERVAL_MS);

  return (
    <p role="status" className="flex items-center gap-2 text-sm">
      <LoaderCircle aria-hidden className="size-4 animate-spin text-muted-foreground" />
      실행 중 · {formatProgress(done, planned)} 완료
    </p>
  );
}
