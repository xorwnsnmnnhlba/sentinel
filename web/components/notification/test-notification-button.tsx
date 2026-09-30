"use client";

import { Send } from "lucide-react";
import { useState, useTransition } from "react";

import { Button } from "@/components/ui/button";
import type { TestNotificationState } from "@/lib/actions/catalog";
import { deliveryMessage } from "@/lib/webhook";

/**
 * 프로젝트 Webhook으로 연결 확인 알림 발송. 결과(받았는지·상태 코드·사유)를 버튼 옆에 표시
 */
export function TestNotificationButton({ action }: { action: () => Promise<TestNotificationState> }) {
  const [result, setResult] = useState<{ ok: boolean; message: string }>();
  const [pending, startTransition] = useTransition();

  function send() {
    setResult(undefined);
    startTransition(async () => {
      const state = await action();
      if (state?.delivery) {
        setResult({ ok: state.delivery.delivered, message: deliveryMessage(state.delivery) });
      } else {
        setResult({ ok: false, message: state?.error ?? "요청을 처리하지 못했습니다" });
      }
    });
  }

  return (
    <div className="space-y-2">
      <Button variant="outline" size="sm" onClick={send} disabled={pending}>
        <Send />
        {pending ? "보내는 중…" : "테스트 알림 보내기"}
      </Button>
      {/* 결과가 바뀌면 화면 낭독기가 읽도록 항상 두는 영역 */}
      <p role="status" className={result?.ok === false ? "text-sm text-destructive" : "text-sm text-muted-foreground"}>
        {result?.message}
      </p>
    </div>
  );
}
