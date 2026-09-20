import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";

import { SESSION_COOKIE } from "@/lib/api/session";

/**
 * 세션 쿠키가 없으면 화면 렌더링 전에 로그인 화면으로 보냄 (Next.js 16에서 middleware → proxy로 변경).
 * 쿠키 유효성은 Spring이 판단하므로 여기서는 존재 여부만 확인
 */
export function proxy(request: NextRequest) {
  if (request.cookies.has(SESSION_COOKIE)) {
    return NextResponse.next();
  }
  const login = new URL("/login", request.url);
  const from = request.nextUrl.pathname + request.nextUrl.search;
  if (from !== "/") {
    login.searchParams.set("from", from);
  }
  return NextResponse.redirect(login);
}

export const config = {
  // 로그인 화면과 정적 자원은 제외. /api/**(BFF 프록시)는 Spring이 401로 판단
  matcher: ["/((?!login|api|_next/static|_next/image|favicon.ico).*)"],
};
