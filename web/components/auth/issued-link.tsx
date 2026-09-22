"use client";

import { Check, Copy } from "lucide-react";
import { useState } from "react";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { formatDateTime } from "@/lib/format";

/**
 * 발급된 초대·재설정 링크. 서버에 저장되지 않으므로 이 화면에서만 확인 가능하며 직접 전달해야 함
 */
export function IssuedLinkBox({ link, expiresAt }: { link: string; expiresAt?: string }) {
  const [copied, setCopied] = useState(false);

  async function copy() {
    try {
      await navigator.clipboard.writeText(link);
      setCopied(true);
    } catch {
      // 클립보드 권한이 없으면 입력칸에서 직접 복사
      setCopied(false);
    }
  }

  return (
    <div className="space-y-2 rounded-md border p-3">
      <p className="text-xs text-muted-foreground">
        링크는 지금만 확인할 수 있습니다. 복사해 본인에게 직접 전달해 주세요
        {expiresAt && ` (만료 ${formatDateTime(expiresAt)} KST)`}
      </p>
      <div className="flex gap-2">
        <Input readOnly value={link} aria-label="발급된 링크" className="font-mono text-xs" />
        <Button type="button" variant="outline" size="icon" onClick={copy} aria-label="링크 복사">
          {copied ? <Check /> : <Copy />}
        </Button>
      </div>
    </div>
  );
}
