import type { Metadata } from "next";
import { Geist_Mono } from "next/font/google";

import { AppShell } from "@/components/app-shell";

import { Providers } from "./providers";
// 한글·영문 공통 글꼴. 동적 서브셋이라 화면에 쓰인 글자 범위의 파일만 내려받음 (자체 배포, CSP font-src 'self')
import "pretendard/dist/web/variable/pretendardvariable-dynamic-subset.css";
import "./globals.css";

const geistMono = Geist_Mono({
  variable: "--font-geist-mono",
  subsets: ["latin"],
});

export const metadata: Metadata = {
  title: "Sentinel",
  description: "API 검증의 정시 자동 실행 및 결과 이력 공유 도구",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="ko" className={`${geistMono.variable} h-full antialiased`}>
      <body className="flex min-h-full flex-col">
        <Providers>
          <AppShell>{children}</AppShell>
        </Providers>
      </body>
    </html>
  );
}
