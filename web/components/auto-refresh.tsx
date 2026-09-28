"use client";

import { useRouter } from "next/navigation";
import { useEffect } from "react";

/**
 * 화면을 주기적으로 다시 받음 (서버 컴포넌트 재실행). 진행 중인 실행처럼 서버에서 바뀌는 상태를 따라가기 위함
 * 서버가 이 훅을 쓰는 컴포넌트를 더 이상 그리지 않거나 until이 지나면 멈춤
 * @param until ISO 시각. 지정 시 이 시각 이후로는 새로 고치지 않음
 */
export function usePeriodicRefresh(intervalMs: number, until?: string) {
  const router = useRouter();

  useEffect(() => {
    const deadline = until === undefined ? Number.POSITIVE_INFINITY : Date.parse(until);
    const timer = setInterval(() => {
      if (Date.now() >= deadline) {
        clearInterval(timer);
        return;
      }
      router.refresh();
    }, intervalMs);
    return () => clearInterval(timer);
  }, [router, intervalMs, until]);
}

/** 화면 표시 없이 주기적으로 새로 고침 */
export function AutoRefresh({ intervalMs, until }: { intervalMs: number; until?: string }) {
  usePeriodicRefresh(intervalMs, until);
  return null;
}
