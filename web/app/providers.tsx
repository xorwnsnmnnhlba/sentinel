"use client";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { setNonce } from "get-nonce";
import { useState } from "react";

/**
 * Radix 대화상자의 스크롤 잠금이 넣는 <style>에 CSP nonce 부여 (react-style-singleton이 get-nonce로 읽음)
 * 서버 렌더링마다 nonce가 바뀌므로(router.refresh 등) 처음 받은 문서의 스크립트에서 읽음. 브라우저는 nonce 속성을 숨기지만 속성값(nonce)은 유지
 */
if (typeof document !== "undefined") {
  const nonce = document.querySelector<HTMLScriptElement>("script[nonce]")?.nonce;
  if (nonce) {
    setNonce(nonce);
  }
}

export function Providers({ children }: { children: React.ReactNode }) {
  // 요청마다 공유되지 않도록 컴포넌트 인스턴스별 생성
  const [queryClient] = useState(
    () =>
      new QueryClient({
        defaultOptions: { queries: { staleTime: 10_000, refetchOnWindowFocus: false } },
      }),
  );
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}
