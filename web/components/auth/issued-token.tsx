"use client";

import { Check, Copy } from "lucide-react";
import { useState } from "react";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { formatDateTime } from "@/lib/format";

/**
 * 발급된 API 토큰. 서버에 원문이 없으므로 이 화면에서만 확인 가능. CI의 비밀 값(예: GitHub Actions secrets)에 저장
 */
export function IssuedTokenBox({ token, expiresAt }: { token: string; expiresAt?: string }) {
  const [copied, setCopied] = useState(false);

  async function copy() {
    try {
      await navigator.clipboard.writeText(token);
      setCopied(true);
    } catch {
      // 클립보드 권한이 없으면 입력칸에서 직접 복사
      setCopied(false);
    }
  }

  return (
    <div className="space-y-2 rounded-md border p-3">
      <p className="text-xs text-muted-foreground">
        토큰은 지금만 확인할 수 있습니다. CI의 비밀 값(예: GitHub Actions secrets의 SENTINEL_TOKEN)에 저장해 주세요
        {expiresAt && ` (만료 ${formatDateTime(expiresAt)} KST)`}
      </p>
      <div className="flex gap-2">
        <Input readOnly value={token} aria-label="발급된 토큰" className="font-mono text-xs" />
        <Button type="button" variant="outline" size="icon" onClick={copy} aria-label="토큰 복사">
          {copied ? <Check /> : <Copy />}
        </Button>
      </div>
    </div>
  );
}
