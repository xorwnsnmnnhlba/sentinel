import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";

import { SESSION_COOKIE } from "@/lib/api/session";
import { contentSecurityPolicy, createNonce } from "@/lib/csp";

/** 로그인 전에 여는 화면. 초대·재설정 링크는 토큰이 자격증명 */
const PUBLIC_PATHS = /^\/(login|invites|password-resets)(\/|$)/;

/**
 * 모든 화면 요청의 앞단 (Next.js 16에서 middleware → proxy로 변경)
 * 1. 로그인이 필요한 화면인데 세션 쿠키가 없으면 렌더링 전에 로그인 화면으로 보냄. 쿠키 유효성은 Spring이 판단
 * 2. 요청마다 새 nonce로 Content-Security-Policy를 만들어 요청(Next.js가 nonce를 읽음)과 응답 헤더에 설정
 *    링크 미리 불러오기는 문서를 만들지 않으므로 제외 (Next.js 문서 권장)
 */
export function proxy(request: NextRequest) {
  const { pathname, search } = request.nextUrl;
  if (!PUBLIC_PATHS.test(pathname) && !request.cookies.has(SESSION_COOKIE)) {
    const login = new URL("/login", request.url);
    const from = pathname + search;
    if (from !== "/") {
      login.searchParams.set("from", from);
    }
    return NextResponse.redirect(login);
  }
  if (isPrefetch(request)) {
    return NextResponse.next();
  }

  const policy = contentSecurityPolicy(createNonce(), process.env.NODE_ENV === "development");
  const requestHeaders = new Headers(request.headers);
  requestHeaders.set("Content-Security-Policy", policy);
  const response = NextResponse.next({ request: { headers: requestHeaders } });
  response.headers.set("Content-Security-Policy", policy);
  return response;
}

function isPrefetch(request: NextRequest): boolean {
  return request.headers.has("next-router-prefetch") || request.headers.get("purpose") === "prefetch";
}

export const config = {
  // 정적 자원과 /api/**(BFF 프록시, JSON 응답이며 Spring이 401로 판단)는 제외.
  // api/처럼 /까지 적어야 /api-docs 같은 화면이 빠지지 않음 (빠지면 CSP·로그인 확인 없이 렌더링)
  matcher: ["/((?!api/|_next/static|_next/image|favicon.ico).*)"],
};
