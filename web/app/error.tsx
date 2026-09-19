"use client";

import { Button } from "@/components/ui/button";

/**
 * 서버 컴포넌트 오류는 운영 빌드에서 메시지가 가려지므로(digest만 전달) 고정 문구 표시. retry()는 데이터 재조회 후 재렌더링
 */
export default function Error({ error, retry }: { error: Error & { digest?: string }; retry: () => void }) {
  return (
    <div className="space-y-4 py-16 text-center">
      <h1 className="text-xl font-semibold">데이터를 불러오지 못했습니다</h1>
      <p className="text-sm text-muted-foreground">API 서버 상태를 확인한 뒤 다시 시도해 주세요</p>
      {error.digest && <p className="font-mono text-xs text-muted-foreground">오류 ID: {error.digest}</p>}
      <Button variant="outline" onClick={() => retry()}>
        다시 시도
      </Button>
    </div>
  );
}
